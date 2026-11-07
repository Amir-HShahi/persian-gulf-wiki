package com.persiangulfwiki.core.media;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.jobqueue.JobQueueTestSupport;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaAttribution;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pipeline results coming back into core, against real Postgres, Redis and MinIO. Every item
// gets to PROCESSING through the real reserve -> PUT -> complete flow, so it holds a real jobs
// row; the test then plays the Go worker by finishing that row by hand (JobQueueTestSupport) and
// runs the real result poll. Nothing here runs or depends on the worker.
//
// The scheduled poll runs every second here too, beside the direct poll() calls; applying is
// serialized by the item's row lock, so the tests assert on the final state, whichever got there.
//
// Structured like the other flow tests: helpers, then the headline flow (smoke), then each
// outcome and each way a result is refused or ignored, then the sweeps.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MediaProcessingFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Random RANDOM = new Random();
    private static final Duration APPLIED_WITHIN = Duration.ofSeconds(15);
    private static final String PLACEHOLDER = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private MediaResultPollJob mediaResultPollJob;

    @Autowired
    private MediaProcessingService mediaProcessingService;

    @Autowired
    private MediaCleanupJob mediaCleanupJob;

    @Value("${app.storage.staging-bucket}")
    private String stagingBucket;

    @Value("${app.storage.media-bucket}")
    private String mediaBucket;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    // An item the real flow has moved to PROCESSING, with the jobs row core queued for it.
    private record Processing(UUID articleId, UUID mediaId, long jobId, byte[] file) {
    }

    private Account verifiedAccount(boolean isModerator) throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "processing-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("proc_" + slug, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        // Before login: roles are baked into the access token.
        if (isModerator) {
            userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        }
        Cookie access = mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        return new Account(user.getId(), access);
    }

    private UUID createArticle(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, "fa",
                "processing-" + UUID.randomUUID(), "Title", objectMapper.readTree("{\"text\":\"body\"}"), null, null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    // --- Upload helpers: reserve -> PUT -> complete, exactly as a client does it ---

    private static String sha256(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] randomFile(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private Processing uploadToProcessing(Account uploader, UUID articleId, String type) throws Exception {
        byte[] file = randomFile(2048);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("contentType", "image/jpeg");
        body.put("bytes", file.length);
        body.put("sha256", sha256(file));
        body.put("metadata", Map.of("descriptions", Map.of("en", "A test shot")));
        String reserved = mockMvc.perform(post("/api/articles/" + articleId + "/media")
                        .cookie(uploader.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID mediaId = UUID.fromString(JsonPath.read(reserved, "$.data.mediaId"));
        Map<String, String> headers = JsonPath.read(reserved, "$.data.requiredHeaders");

        HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(JsonPath.read(reserved, "$.data.uploadUrl")))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(file));
        headers.forEach(put::header);
        assertThat(HTTP.send(put.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        mockMvc.perform(post("/api/articles/" + articleId + "/media/" + mediaId + "/complete")
                        .cookie(uploader.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("PROCESSING"));
        Long jobId = JobQueueTestSupport.processingJobIdOf(jdbcTemplate, mediaId);
        assertThat(jobId).isNotNull();
        return new Processing(articleId, mediaId, jobId, file);
    }

    // --- Worker-side helpers: results as the Go worker writes them ---

    private static Map<String, Object> result(Processing item, String type, String outcome) {
        return JobQueueTestSupport.envelope(item.jobId(), type, outcome);
    }

    private static Map<String, Object> okResult(Processing item, String verifiedSha256) {
        return okResult(item, verifiedSha256, null);
    }

    private static Map<String, Object> okResult(Processing item, String verifiedSha256,
            @Nullable Map<String, Object> attribution) {
        String prefix = MediaProcessingService.pendingVariantPrefix(item.mediaId());
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("width", 4000);
        image.put("height", 3000);
        image.put("format", "jpeg");
        image.put("sha256", verifiedSha256);
        image.put("placeholder", PLACEHOLDER);
        if (attribution != null) {
            image.put("attribution", attribution);
        }
        image.put("variants", List.of(
                variant("w640", prefix + "w640.webp", 640, 480),
                variant("w1280", prefix + "w1280.webp", 1280, 960)));
        image.put("original", Map.of("key", MediaProcessingService.originalPrefix(item.mediaId()) + "original.jpg",
                "bytes", 2048, "content_type", "image/jpeg"));
        Map<String, Object> result = result(item, "image", "ok");
        result.put("image", image);
        return result;
    }

    private static Map<String, Object> variant(String label, String key, int width, int height) {
        return Map.of("label", label, "format", "webp", "key", key, "bytes", 20480, "width", width, "height", height);
    }

    private void finishDone(Processing item, Map<String, Object> result) throws Exception {
        JobQueueTestSupport.finish(jdbcTemplate, item.jobId(), QueueJob.STATUS_DONE,
                objectMapper.writeValueAsString(result), null);
    }

    private ArticleMedia pollUntil(UUID mediaId, ProcessingStatus expected) {
        mediaResultPollJob.poll();
        await().atMost(APPLIED_WITHIN).until(() ->
                articleMediaRepository.findById(mediaId).orElseThrow().getProcessingStatus() == expected);
        return articleMediaRepository.findById(mediaId).orElseThrow();
    }

    private List<ModerationTask> tasksFor(UUID mediaId) {
        List<UUID> versionIds = mediaMetadataVersionRepository.findAll().stream()
                .filter(version -> version.getMediaId().equals(mediaId))
                .map(MediaMetadataVersion::getId)
                .toList();
        return moderationTaskRepository.findAll().stream()
                .filter(task -> versionIds.contains(task.getMediaMetadataVersionId()))
                .toList();
    }

    private boolean mediaObjectExists(String key) {
        try {
            s3Client.headObject(builder -> builder.bucket(mediaBucket).key(key));
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    private boolean stagingObjectExists(UUID mediaId) {
        try {
            s3Client.headObject(builder -> builder.bucket(stagingBucket).key(mediaId.toString()));
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    private void putMediaObject(String key) {
        s3Client.putObject(PutObjectRequest.builder().bucket(mediaBucket).key(key).contentType("image/webp").build(),
                RequestBody.fromBytes(new byte[] {1, 2, 3}));
    }

    private void claimAndDecide(Account moderator, UUID taskId, Decision decision, @Nullable String reason)
            throws Exception {
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderator.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(decision, reason))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));
    }

    // --- Smoke: the headline flow ---

    @Test
    void okResultMakesTheItemReadyOpensItsModerationTaskAndApprovalPublishesIt() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");
        // No task while the file is unprocessed, and nothing to apply while the job is pending.
        assertThat(tasksFor(item.mediaId())).isEmpty();
        mediaResultPollJob.poll();
        assertThat(articleMediaRepository.findById(item.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(stagingObjectExists(item.mediaId())).isTrue();

        // The worker keeps its private copy of the upload and writes the variants.
        String original = MediaProcessingService.originalPrefix(item.mediaId()) + "original.jpg";
        putMediaObject(original);
        String verified = sha256(item.file());
        finishDone(item, okResult(item, verified));

        ArticleMedia ready = pollUntil(item.mediaId(), ProcessingStatus.READY);
        assertThat(ready.getSha256()).isEqualTo(verified);
        assertThat(ready.getPlaceholder()).isEqualTo(PLACEHOLDER);
        assertThat(ready.getFailureCode()).isNull();
        assertThat(ready.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(ready.getAttribution()).isNull();
        assertThat(ready.getVariants()).extracting("size").containsExactly("w640", "w1280");
        assertThat(ready.getVariants().getFirst().key())
                .isEqualTo(MediaProcessingService.pendingVariantPrefix(item.mediaId()) + "w640.webp");
        // The staging upload is a second copy once READY, so it goes after the commit; the
        // worker's private original stays.
        await().atMost(APPLIED_WITHIN).until(() -> !stagingObjectExists(item.mediaId()));
        assertThat(mediaObjectExists(original)).isTrue();

        // Exactly one task, on the first metadata version, waiting in the queue.
        List<ModerationTask> tasks = tasksFor(item.mediaId());
        assertThat(tasks).hasSize(1);
        ModerationTask task = tasks.getFirst();
        assertThat(task.getState()).isEqualTo(ModerationTaskState.OPEN);
        assertThat(task.getRevisionId()).isNull();

        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + item.mediaId())
                        .cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("READY"))
                .andExpect(jsonPath("$.data.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.data.failureMessage").value(nullValue()));

        // The task opened here is an ordinary queue task: a moderator claims and approves it,
        // which publishes the item. Approval moves the variants to their public keys, so the
        // objects the worker would have written must exist; this test plays the worker.
        ready.getVariants().forEach(variant -> putMediaObject(variant.key()));
        Account moderator = verifiedAccount(true);
        mockMvc.perform(get("/api/moderation/tasks/" + task.getId()).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution").value(nullValue()))
                .andExpect(jsonPath("$.data.revisionChecks").value(nullValue()));
        claimAndDecide(moderator, task.getId(), Decision.APPROVE, null);
        ArticleMedia published = articleMediaRepository.findById(item.mediaId()).orElseThrow();
        assertThat(published.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.isPubliclyVisible()).isTrue();
        assertThat(mediaObjectExists("public/" + item.mediaId() + "/w1280.webp")).isTrue();
        assertThat(mediaObjectExists(MediaProcessingService.pendingVariantPrefix(item.mediaId()) + "w1280.webp"))
                .isFalse();
        assertThat(mediaMetadataVersionRepository.findById(task.getMediaMetadataVersionId()).orElseThrow().getStatus())
                .isEqualTo(MetadataVersionStatus.APPROVED);
    }

    // What the file claims about its author reaches the moderator's review, cleaned: a bidi
    // override is dropped, a line break becomes a space, and a licence URL that is not a web
    // link is left out.
    @Test
    void okResultStoresTheFilesCleanedAttributionAndTheModeratorSeesIt() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "PANORAMA_360");

        Map<String, Object> attribution = new LinkedHashMap<>();
        attribution.put("author", "مریم‮ احمدی");
        attribution.put("copyright", "© 2026\nمریم احمدی");
        attribution.put("license", "CC BY-SA 4.0");
        attribution.put("license_url", "javascript:alert(1)");
        attribution.put("created_at", "2024-05-01T10:20:30");
        Map<String, Object> ok = okResult(item, sha256(item.file()), attribution);
        ok.put("type", "panorama");
        finishDone(item, ok);

        ArticleMedia ready = pollUntil(item.mediaId(), ProcessingStatus.READY);
        assertThat(ready.getAttribution()).isEqualTo(new MediaAttribution("مریم احمدی", null, "© 2026 مریم احمدی",
                "CC BY-SA 4.0", null, "2024-05-01T10:20:30"));

        ModerationTask task = tasksFor(item.mediaId()).getFirst();
        Account moderator = verifiedAccount(true);
        mockMvc.perform(get("/api/moderation/tasks/" + task.getId()).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.author").value("مریم احمدی"))
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.credit").value(nullValue()))
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.copyright").value("© 2026 مریم احمدی"))
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.license").value("CC BY-SA 4.0"))
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.licenseUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.mediaReview.fileAttribution.createdAt").value("2024-05-01T10:20:30"))
                .andExpect(jsonPath("$.data.subjectReview").value(nullValue()))
                .andExpect(jsonPath("$.data.revisionChecks").value(nullValue()));
    }

    // --- The other outcomes ---

    @Test
    void rejectedResultFailsTheItemWithItsReasonCodeTranslated() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "PANORAMA_360");

        Map<String, Object> rejected = result(item, "panorama", "rejected");
        rejected.put("reason", Map.of("code", "panorama_aspect_ratio", "detail", Map.of("width", 4000, "height", 3000)));
        finishDone(item, rejected);

        ArticleMedia failed = pollUntil(item.mediaId(), ProcessingStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("panorama_aspect_ratio");
        assertThat(tasksFor(item.mediaId())).isEmpty();
        // Only READY lets go of the staging upload.
        assertThat(stagingObjectExists(item.mediaId())).isTrue();

        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + item.mediaId())
                        .cookie(uploader.accessCookie())
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureCode").value("panorama_aspect_ratio"))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("A 360° image must be exactly twice as wide as it is tall. Please upload a complete panorama."));
        // Farsi is the default locale.
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + item.mediaId())
                        .cookie(uploader.accessCookie()))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("عرض تصویر ۳۶۰ درجه باید دقیقاً دو برابر ارتفاع آن باشد. لطفاً یک تصویر پانورامای کامل بارگذاری فرمایید"));
    }

    // failure_code only ever holds codes core can translate: an unagreed pipeline code (such as
    // the worker's object_missing), or one of core's own codes claimed by the worker, is stored as
    // the generic "rejected".
    @Test
    void rejectionWithAnUnknownOrCoreOwnedCodeIsStoredAsGenericRejected() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing unknown = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing coreOwned = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> unknownResult = result(unknown, "image", "rejected");
        unknownResult.put("reason", Map.of("code", "object_missing"));
        finishDone(unknown, unknownResult);
        Map<String, Object> coreOwnedResult = result(coreOwned, "image", "rejected");
        coreOwnedResult.put("reason", Map.of("code", "duplicate"));
        finishDone(coreOwned, coreOwnedResult);

        assertThat(pollUntil(unknown.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("rejected");
        assertThat(pollUntil(coreOwned.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("rejected");
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + unknown.mediaId())
                        .cookie(uploader.accessCookie())
                        .header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("This file was not accepted during processing. Please upload a different file."));
    }

    // A failed row is the worker having given up after its retries; it carries no result.
    @Test
    void failedJobFailsTheItemAsProcessingError() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");

        JobQueueTestSupport.finish(jdbcTemplate, item.jobId(), QueueJob.STATUS_FAILED, null,
                "minio: connection refused");

        assertThat(pollUntil(item.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("processing_error");
        assertThat(tasksFor(item.mediaId())).isEmpty();
    }

    // The reservation-time check only sees declared hashes; this is the one that sees verified
    // ones. The second item declared a different file, but the pipeline found the same one.
    @Test
    void okResultWhoseVerifiedHashIsAlreadyLiveInTheArticleFailsAsDuplicate() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing first = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing second = uploadToProcessing(uploader, articleId, "IMAGE");
        String sameFile = sha256(first.file());

        finishDone(first, okResult(first, sameFile));
        pollUntil(first.mediaId(), ProcessingStatus.READY);
        finishDone(second, okResult(second, sameFile));

        ArticleMedia duplicate = pollUntil(second.mediaId(), ProcessingStatus.FAILED);
        assertThat(duplicate.getFailureCode()).isEqualTo("duplicate");
        assertThat(duplicate.getSha256()).isNull();
        assertThat(duplicate.getVariants()).isEmpty();
        assertThat(tasksFor(second.mediaId())).isEmpty();
        assertThat(tasksFor(first.mediaId())).hasSize(1);

        // The same file in a *different* article is not a duplicate.
        UUID otherArticle = createArticle(uploader);
        Processing elsewhere = uploadToProcessing(uploader, otherArticle, "IMAGE");
        finishDone(elsewhere, okResult(elsewhere, sameFile));
        assertThat(pollUntil(elsewhere.mediaId(), ProcessingStatus.READY).getSha256()).isEqualTo(sameFile);
    }

    // --- Results core refuses or ignores ---

    // The variant-key rule is the security-relevant one: core later copies and deletes these
    // keys, so one outside the item's own prefix must never be stored.
    @Test
    void okResultWithAnInvalidPayloadOrMismatchedEnvelopeFailsAsProcessingErrorAndStoresNothing() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing foreignKey = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing badSha = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing svgPlaceholder = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing wrongType = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing wrongJob = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing unknownOutcome = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> foreign = okResult(foreignKey, sha256(foreignKey.file()));
        foreign.put("image", Map.of("sha256", sha256(foreignKey.file()), "placeholder", PLACEHOLDER,
                "variants", List.of(variant("w640", "public/" + UUID.randomUUID() + "/w640.webp", 640, 480))));
        finishDone(foreignKey, foreign);

        finishDone(badSha, okResult(badSha, "not-a-hash"));

        Map<String, Object> svg = okResult(svgPlaceholder, sha256(svgPlaceholder.file()));
        String prefix = MediaProcessingService.pendingVariantPrefix(svgPlaceholder.mediaId());
        svg.put("image", Map.of("sha256", sha256(svgPlaceholder.file()),
                "placeholder", "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                "variants", List.of(variant("w640", prefix + "w640.webp", 640, 480))));
        finishDone(svgPlaceholder, svg);

        Map<String, Object> panoramaForAnImage = okResult(wrongType, sha256(wrongType.file()));
        panoramaForAnImage.put("type", "panorama");
        finishDone(wrongType, panoramaForAnImage);

        Map<String, Object> otherJobsResult = okResult(wrongJob, sha256(wrongJob.file()));
        otherJobsResult.put("job_id", wrongJob.jobId() + 1_000_000);
        finishDone(wrongJob, otherJobsResult);

        finishDone(unknownOutcome, result(unknownOutcome, "image", "deferred"));

        mediaResultPollJob.poll();
        for (Processing item : List.of(foreignKey, badSha, svgPlaceholder, wrongType, wrongJob, unknownOutcome)) {
            ArticleMedia failed = pollUntil(item.mediaId(), ProcessingStatus.FAILED);
            assertThat(failed.getFailureCode()).isEqualTo("processing_error");
            assertThat(failed.getVariants()).isEmpty();
            assertThat(failed.getSha256()).isNull();
            assertThat(failed.getPlaceholder()).isNull();
            assertThat(failed.getAttribution()).isNull();
            assertThat(tasksFor(item.mediaId())).isEmpty();
        }
    }

    // jsonb guarantees the column is JSON, not that it is a result: a done row with no result,
    // with JSON of another shape, or from a contract version this core does not know fails its
    // item -- left PROCESSING it would be read again on every poll -- without holding up a good
    // row applied in the same run.
    @Test
    void unreadableOrUnknownVersionResultsFailTheirItemWithoutHoldingUpOthers() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing noResult = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing notAnObject = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing mistyped = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing futureVersion = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing good = uploadToProcessing(uploader, articleId, "IMAGE");

        JobQueueTestSupport.finish(jdbcTemplate, noResult.jobId(), QueueJob.STATUS_DONE, null, null);
        JobQueueTestSupport.finish(jdbcTemplate, notAnObject.jobId(), QueueJob.STATUS_DONE, "[1, 2, 3]", null);
        JobQueueTestSupport.finish(jdbcTemplate, mistyped.jobId(), QueueJob.STATUS_DONE,
                "{\"version\": \"one\", \"job_id\": " + mistyped.jobId() + "}", null);
        Map<String, Object> v2 = okResult(futureVersion, sha256(futureVersion.file()));
        v2.put("version", 2);
        finishDone(futureVersion, v2);
        finishDone(good, okResult(good, sha256(good.file())));

        mediaResultPollJob.poll();
        for (Processing item : List.of(noResult, notAnObject, mistyped, futureVersion)) {
            assertThat(pollUntil(item.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("processing_error");
        }
        assertThat(pollUntil(good.mediaId(), ProcessingStatus.READY).getFailureCode()).isNull();
    }

    // A result is applied only to an item that is still PROCESSING and still waiting on that
    // job: a second apply of the same row (another core instance, a retry), a contradicting one
    // for the same job, one naming another job, and one for a deleted item all change nothing.
    @Test
    void resultsForAnotherJobOrAnAlreadySettledOrDeletedItemAreIgnored() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");

        mediaProcessingService.applyResult(item.mediaId(), item.jobId() + 1_000_000, QueueJob.STATUS_FAILED, null,
                "a job this item never waited on");
        assertThat(articleMediaRepository.findById(item.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);

        Map<String, Object> okResult = okResult(item, sha256(item.file()));
        finishDone(item, okResult);
        pollUntil(item.mediaId(), ProcessingStatus.READY);

        mediaProcessingService.applyResult(item.mediaId(), item.jobId(), QueueJob.STATUS_DONE,
                objectMapper.writeValueAsString(okResult), null);
        mediaProcessingService.applyResult(item.mediaId(), item.jobId(), QueueJob.STATUS_FAILED, null, "late");
        mediaResultPollJob.poll();
        ArticleMedia settled = articleMediaRepository.findById(item.mediaId()).orElseThrow();
        assertThat(settled.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(settled.getFailureCode()).isNull();
        assertThat(tasksFor(item.mediaId())).hasSize(1);

        // A finished row whose item no longer exists is never selected again, and applying it
        // anyway is a no-op.
        Processing deleted = uploadToProcessing(uploader, articleId, "IMAGE");
        articleMediaRepository.deleteById(deleted.mediaId());
        finishDone(deleted, okResult(deleted, sha256(deleted.file())));
        mediaResultPollJob.poll();
        mediaProcessingService.applyResult(deleted.mediaId(), deleted.jobId(), QueueJob.STATUS_DONE,
                objectMapper.writeValueAsString(okResult(deleted, sha256(deleted.file()))), null);
        assertThat(articleMediaRepository.existsById(deleted.mediaId())).isFalse();
    }

    // --- Sweeps ---

    @Test
    void stuckProcessingTimesOutLateResultsAreIgnoredAndTheFailedSweepDeletesWhatWasLeft() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing stuck = uploadToProcessing(uploader, articleId, "IMAGE");

        assertThat(mediaProcessingService.failStuckProcessing(Instant.now().plusSeconds(1))).isPositive();
        ArticleMedia timedOut = articleMediaRepository.findById(stuck.mediaId()).orElseThrow();
        assertThat(timedOut.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(timedOut.getFailureCode()).isEqualTo("processing_timeout");

        // The worker finishes after all: it writes its variants and its private original and
        // records the result, and core ignores the result. The staging upload is still there too
        // (the worker never deletes from staging).
        String prefix = MediaProcessingService.pendingVariantPrefix(stuck.mediaId());
        String original = MediaProcessingService.originalPrefix(stuck.mediaId()) + "original.jpg";
        putMediaObject(prefix + "w640.webp");
        putMediaObject(original);
        finishDone(stuck, okResult(stuck, sha256(stuck.file())));
        mediaResultPollJob.poll();
        assertThat(articleMediaRepository.findById(stuck.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(stagingObjectExists(stuck.mediaId())).isTrue();

        // Inside the grace period nothing is deleted; past it, the row and every object go.
        assertThat(mediaCleanupJob.sweepFailed(Instant.now().minus(Duration.ofDays(1)))).isZero();
        assertThat(articleMediaRepository.existsById(stuck.mediaId())).isTrue();
        assertThat(mediaCleanupJob.sweepFailed(Instant.now().plusSeconds(1))).isPositive();
        assertThat(articleMediaRepository.existsById(stuck.mediaId())).isFalse();
        assertThat(mediaObjectExists(prefix + "w640.webp")).isFalse();
        assertThat(mediaObjectExists(original)).isFalse();
        assertThat(stagingObjectExists(stuck.mediaId())).isFalse();
    }

    // The worker's private copy of an upload goes with a rejected item as it does with a failed
    // one: nothing of an upload a moderator turned down outlives the item.
    @Test
    void rejectedSweepDeletesTheWorkersPrivateOriginal() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");
        String original = MediaProcessingService.originalPrefix(item.mediaId()) + "original.jpg";
        putMediaObject(original);
        finishDone(item, okResult(item, sha256(item.file())));
        ArticleMedia ready = pollUntil(item.mediaId(), ProcessingStatus.READY);
        ready.getVariants().forEach(variant -> putMediaObject(variant.key()));

        Account moderator = verifiedAccount(true);
        claimAndDecide(moderator, tasksFor(item.mediaId()).getFirst().getId(), Decision.REJECT, "Not this one.");
        assertThat(articleMediaRepository.findById(item.mediaId()).orElseThrow().getPublicationStatus())
                .isEqualTo(PublicationStatus.REJECTED);
        assertThat(mediaObjectExists(original)).isTrue();

        assertThat(mediaCleanupJob.sweepRejected(Instant.now().plusSeconds(1))).isPositive();
        assertThat(articleMediaRepository.existsById(item.mediaId())).isFalse();
        assertThat(mediaObjectExists(original)).isFalse();
        assertThat(mediaObjectExists(ready.getVariants().getFirst().key())).isFalse();
    }
}

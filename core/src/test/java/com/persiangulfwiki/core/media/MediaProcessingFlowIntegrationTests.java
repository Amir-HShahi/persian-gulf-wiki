package com.persiangulfwiki.core.media;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.pipeline.MediaResultConsumer;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
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
// gets to PROCESSING through the real reserve -> PUT -> complete flow, so it holds a real job id;
// the test then plays the Go worker by writing results onto the results stream by hand, and
// waits for the live consumer to apply them. Nothing here runs or depends on the worker.
//
// Structured like the other flow tests: helpers, then the headline flow (smoke), then each
// outcome and each way a result is refused or ignored, then delivery-level failure handling,
// then the sweeps.
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private MediaResultConsumer mediaResultConsumer;

    @Autowired
    private MediaProcessingService mediaProcessingService;

    @Autowired
    private MediaCleanupJob mediaCleanupJob;

    @Value("${app.storage.staging-bucket}")
    private String stagingBucket;

    @Value("${app.storage.media-bucket}")
    private String mediaBucket;

    @Value("${app.media.job-stream}")
    private String jobStream;

    @Value("${app.media.result-stream}")
    private String resultStream;

    @Value("${app.media.result-consumer-group}")
    private String consumerGroup;

    @Value("${app.media.result-max-deliveries}")
    private long maxDeliveries;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    // An item the real flow has moved to PROCESSING, with the job core queued for it.
    private record Processing(UUID articleId, UUID mediaId, String jobId, byte[] file) {
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
        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                "processing-" + UUID.randomUUID(), "Title", objectMapper.readTree("{\"text\":\"body\"}"), null);
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
        return new Processing(articleId, mediaId, jobIdFor(mediaId), file);
    }

    private String jobIdFor(UUID mediaId) {
        List<MapRecord<String, Object, Object>> jobs = redisTemplate.opsForStream().read(StreamOffset.fromStart(jobStream));
        return jobs.stream()
                .filter(record -> mediaId.toString().equals(record.getValue().get("submission_id")))
                .map(record -> (String) record.getValue().get("job_id"))
                .findFirst().orElseThrow();
    }

    // --- Worker-side helpers: results as the Go worker writes them ---

    private Map<String, Object> result(Processing item, String type, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("job_id", item.jobId());
        result.put("submission_id", item.mediaId().toString());
        result.put("type", type);
        result.put("status", status);
        result.put("attempt", 1);
        result.put("duration_ms", 1234);
        result.put("finished_at", "2026-09-28T12:00:00Z");
        return result;
    }

    private Map<String, Object> okResult(Processing item, String verifiedSha256) {
        String prefix = MediaProcessingService.pendingVariantPrefix(item.mediaId());
        Map<String, Object> result = result(item, "media.image", "ok");
        result.put("media", Map.of(
                "sha256", verifiedSha256,
                "width", 4000,
                "height", 3000,
                "placeholder", PLACEHOLDER,
                "variants", List.of(
                        variant("640", "webp", prefix + "640.webp", 640, 480),
                        variant("1280", "webp", prefix + "1280.webp", 1280, 960))));
        return result;
    }

    private static Map<String, Object> variant(String size, String format, String key, int width, int height) {
        return Map.of("size", size, "format", format, "key", key, "bytes", 20480, "width", width, "height", height);
    }

    private RecordId publishResult(Map<String, Object> result) throws Exception {
        return publishRaw(Map.of(MediaResultConsumer.RESULT_FIELD, objectMapper.writeValueAsString(result)));
    }

    private RecordId publishRaw(Map<String, String> fields) {
        return redisTemplate.opsForStream().add(StreamRecords.string(fields).withStreamKey(resultStream));
    }

    private ArticleMedia awaitProcessingStatus(UUID mediaId, ProcessingStatus expected) {
        await().atMost(APPLIED_WITHIN).until(() ->
                articleMediaRepository.findById(mediaId).orElseThrow().getProcessingStatus() == expected);
        return articleMediaRepository.findById(mediaId).orElseThrow();
    }

    // "Ignored" has no state change to wait for, so this waits for what ignoring ends in: the
    // entry delivered to core's group and acknowledged.
    private void awaitAcknowledged(RecordId id) {
        await().atMost(APPLIED_WITHIN).until(() -> isDelivered(id)
                && redisTemplate.opsForStream().pending(resultStream, consumerGroup, Range.closed(id.getValue(), id.getValue()), 1).isEmpty());
    }

    private boolean isDelivered(RecordId id) {
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(resultStream);
        return groups.stream()
                .filter(group -> consumerGroup.equals(group.groupName()))
                .anyMatch(group -> compare(RecordId.of(group.lastDeliveredId()), id) >= 0);
    }

    private static int compare(RecordId a, RecordId b) {
        int byTime = Long.compare(a.getTimestamp(), b.getTimestamp());
        return byTime != 0 ? byTime : Long.compare(a.getSequence(), b.getSequence());
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

    // --- Smoke: the headline flow ---

    @Test
    void okResultMakesTheItemReadyOpensItsModerationTaskAndApprovalPublishesIt() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");
        assertThat(articleMediaRepository.findById(item.mediaId()).orElseThrow().getProcessingJobId())
                .hasToString(item.jobId());
        // No task while the file is unprocessed.
        assertThat(tasksFor(item.mediaId())).isEmpty();

        String verified = sha256(item.file());
        RecordId entry = publishResult(okResult(item, verified));

        ArticleMedia ready = awaitProcessingStatus(item.mediaId(), ProcessingStatus.READY);
        assertThat(ready.getSha256()).isEqualTo(verified);
        assertThat(ready.getPlaceholder()).isEqualTo(PLACEHOLDER);
        assertThat(ready.getFailureCode()).isNull();
        assertThat(ready.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(ready.getVariants()).extracting("size").containsExactly("640", "1280");
        assertThat(ready.getVariants().getFirst().key())
                .isEqualTo(MediaProcessingService.pendingVariantPrefix(item.mediaId()) + "640.webp");
        awaitAcknowledged(entry);

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
        ready.getVariants().forEach(variant -> s3Client.putObject(
                PutObjectRequest.builder().bucket(mediaBucket).key(variant.key()).contentType("image/webp").build(),
                RequestBody.fromBytes(new byte[] {1, 2, 3})));
        Account moderator = verifiedAccount(true);
        mockMvc.perform(post("/api/moderation/tasks/" + task.getId() + "/claim")
                        .cookie(moderator.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/moderation/tasks/" + task.getId() + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));
        ArticleMedia published = articleMediaRepository.findById(item.mediaId()).orElseThrow();
        assertThat(published.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.isPubliclyVisible()).isTrue();
        assertThat(mediaObjectExists("public/" + item.mediaId() + "/1280.webp")).isTrue();
        assertThat(mediaObjectExists(MediaProcessingService.pendingVariantPrefix(item.mediaId()) + "1280.webp")).isFalse();
        assertThat(mediaMetadataVersionRepository.findById(task.getMediaMetadataVersionId()).orElseThrow().getStatus())
                .isEqualTo(MetadataVersionStatus.APPROVED);
    }

    // --- The other outcomes ---

    @Test
    void rejectedResultFailsTheItemWithItsReasonCodeTranslated() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "PANORAMA_360");

        Map<String, Object> rejected = result(item, "media.panorama", "rejected");
        rejected.put("reason", Map.of("code", "panorama_aspect_ratio", "detail", Map.of("width", "4000", "height", "3000")));
        publishResult(rejected);

        ArticleMedia failed = awaitProcessingStatus(item.mediaId(), ProcessingStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("panorama_aspect_ratio");
        assertThat(tasksFor(item.mediaId())).isEmpty();

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

    // failure_code only ever holds codes core can translate: an unagreed pipeline code, or one
    // of core's own codes claimed by the worker, is stored as the generic "rejected".
    @Test
    void rejectionWithAnUnknownOrCoreOwnedCodeIsStoredAsGenericRejected() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing unknown = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing coreOwned = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> unknownResult = result(unknown, "media.image", "rejected");
        unknownResult.put("reason", Map.of("code", "too_blurry"));
        publishResult(unknownResult);
        Map<String, Object> coreOwnedResult = result(coreOwned, "media.image", "rejected");
        coreOwnedResult.put("reason", Map.of("code", "duplicate"));
        publishResult(coreOwnedResult);

        assertThat(awaitProcessingStatus(unknown.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("rejected");
        assertThat(awaitProcessingStatus(coreOwned.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("rejected");
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + unknown.mediaId())
                        .cookie(uploader.accessCookie())
                        .header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("This file was not accepted during processing. Please upload a different file."));
    }

    @Test
    void failedResultFailsTheItemAsProcessingError() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> failed = result(item, "media.image", "failed");
        failed.put("attempt", 3);
        failed.put("error", "minio: connection refused");
        publishResult(failed);

        assertThat(awaitProcessingStatus(item.mediaId(), ProcessingStatus.FAILED).getFailureCode())
                .isEqualTo("processing_error");
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

        publishResult(okResult(first, sameFile));
        awaitProcessingStatus(first.mediaId(), ProcessingStatus.READY);
        publishResult(okResult(second, sameFile));

        ArticleMedia duplicate = awaitProcessingStatus(second.mediaId(), ProcessingStatus.FAILED);
        assertThat(duplicate.getFailureCode()).isEqualTo("duplicate");
        assertThat(duplicate.getSha256()).isNull();
        assertThat(duplicate.getVariants()).isEmpty();
        assertThat(tasksFor(second.mediaId())).isEmpty();
        assertThat(tasksFor(first.mediaId())).hasSize(1);

        // The same file in a *different* article is not a duplicate.
        UUID otherArticle = createArticle(uploader);
        Processing elsewhere = uploadToProcessing(uploader, otherArticle, "IMAGE");
        publishResult(okResult(elsewhere, sameFile));
        assertThat(awaitProcessingStatus(elsewhere.mediaId(), ProcessingStatus.READY).getSha256()).isEqualTo(sameFile);
    }

    // --- Results core refuses or ignores ---

    // The variant-key rule is the security-relevant one: core later copies and deletes these
    // keys, so one outside the item's own prefix must never be stored.
    @Test
    void okResultWithAnInvalidPayloadOrMismatchedTypeFailsAsProcessingErrorAndStoresNothing() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing foreignKey = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing badSha = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing svgPlaceholder = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing wrongType = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> foreign = okResult(foreignKey, sha256(foreignKey.file()));
        foreign.put("media", Map.of("sha256", sha256(foreignKey.file()), "placeholder", PLACEHOLDER,
                "variants", List.of(variant("640", "webp", "public/" + UUID.randomUUID() + "/640.webp", 640, 480))));
        publishResult(foreign);

        Map<String, Object> shortSha = okResult(badSha, "not-a-hash");
        publishResult(shortSha);

        Map<String, Object> svg = okResult(svgPlaceholder, sha256(svgPlaceholder.file()));
        String prefix = MediaProcessingService.pendingVariantPrefix(svgPlaceholder.mediaId());
        svg.put("media", Map.of("sha256", sha256(svgPlaceholder.file()),
                "placeholder", "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                "variants", List.of(variant("640", "webp", prefix + "640.webp", 640, 480))));
        publishResult(svg);

        Map<String, Object> panoramaForAnImage = okResult(wrongType, sha256(wrongType.file()));
        panoramaForAnImage.put("type", "media.panorama");
        publishResult(panoramaForAnImage);

        for (Processing item : List.of(foreignKey, badSha, svgPlaceholder, wrongType)) {
            ArticleMedia failed = awaitProcessingStatus(item.mediaId(), ProcessingStatus.FAILED);
            assertThat(failed.getFailureCode()).isEqualTo("processing_error");
            assertThat(failed.getVariants()).isEmpty();
            assertThat(failed.getSha256()).isNull();
            assertThat(failed.getPlaceholder()).isNull();
            assertThat(tasksFor(item.mediaId())).isEmpty();
        }
    }

    // Delivery is at-least-once, and a stale job's result can arrive after a newer job was
    // queued: only a result naming the job the item is waiting on, while it is still waiting,
    // is applied.
    @Test
    void resultsForAnotherJobOrAnAlreadySettledItemAreIgnored() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");

        Map<String, Object> staleJob = result(item, "media.image", "failed");
        staleJob.put("job_id", UUID.randomUUID().toString());
        awaitAcknowledged(publishResult(staleJob));
        assertThat(articleMediaRepository.findById(item.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);

        Map<String, Object> ok = okResult(item, sha256(item.file()));
        publishResult(ok);
        awaitProcessingStatus(item.mediaId(), ProcessingStatus.READY);

        // A redelivery of the same result, and a contradicting one for the same job.
        awaitAcknowledged(publishResult(ok));
        awaitAcknowledged(publishResult(result(item, "media.image", "failed")));
        ArticleMedia settled = articleMediaRepository.findById(item.mediaId()).orElseThrow();
        assertThat(settled.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(settled.getFailureCode()).isNull();
        assertThat(tasksFor(item.mediaId())).hasSize(1);

        // A result for an item that no longer exists is acknowledged and dropped.
        Processing deleted = uploadToProcessing(uploader, articleId, "IMAGE");
        articleMediaRepository.deleteById(deleted.mediaId());
        awaitAcknowledged(publishResult(okResult(deleted, sha256(deleted.file()))));
    }

    // --- Delivery-level failures ---

    @Test
    void unreadableEntriesAreDroppedWithoutHoldingUpLaterResults() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing item = uploadToProcessing(uploader, articleId, "IMAGE");

        RecordId notJson = publishRaw(Map.of(MediaResultConsumer.RESULT_FIELD, "{not json"));
        RecordId noField = publishRaw(Map.of("payload", "{}"));
        Map<String, Object> futureVersion = okResult(item, sha256(item.file()));
        futureVersion.put("version", 2);
        RecordId unsupported = publishResult(futureVersion);
        publishResult(okResult(item, sha256(item.file())));

        awaitProcessingStatus(item.mediaId(), ProcessingStatus.READY);
        awaitAcknowledged(notJson);
        awaitAcknowledged(noField);
        awaitAcknowledged(unsupported);
    }

    // An entry a consumer took and never acknowledged -- it crashed, or applying failed -- is
    // claimed again once idle and applied. One that keeps failing is given up on after the
    // configured deliveries, leaving its item for the stuck-processing sweep.
    //
    // The live consumer is paused so the entries go to a "crashed" consumer instead; it would
    // otherwise take them first.
    @Test
    void entriesLeftPendingByACrashedConsumerAreReclaimedAndPoisonEntriesGivenUpOn() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createArticle(uploader);
        Processing reclaimable = uploadToProcessing(uploader, articleId, "IMAGE");
        Processing poisoned = uploadToProcessing(uploader, articleId, "IMAGE");

        mediaResultConsumer.stop();
        try {
            RecordId first = publishResult(okResult(reclaimable, sha256(reclaimable.file())));
            RecordId second = publishResult(okResult(poisoned, sha256(poisoned.file())));
            Consumer crashed = Consumer.from(consumerGroup, "crashed-" + UUID.randomUUID());
            List<MapRecord<String, Object, Object>> taken = redisTemplate.opsForStream().read(crashed,
                    StreamReadOptions.empty().count(100), StreamOffset.create(resultStream, ReadOffset.lastConsumed()));
            assertThat(taken).extracting(MapRecord::getId).contains(first, second);
            // Redelivered to the crashed consumer until it has used up its deliveries.
            for (long delivery = 1; delivery < maxDeliveries; delivery++) {
                redisTemplate.opsForStream().claim(resultStream, consumerGroup, crashed.getName(), Duration.ZERO, second);
            }

            // Both idle past a zero threshold: the first is claimed and applied, the second
            // dropped.
            assertThat(mediaResultConsumer.reclaimStale(Duration.ZERO)).isPositive();
            assertThat(articleMediaRepository.findById(reclaimable.mediaId()).orElseThrow().getProcessingStatus())
                    .isEqualTo(ProcessingStatus.READY);
            assertThat(articleMediaRepository.findById(poisoned.mediaId()).orElseThrow().getProcessingStatus())
                    .isEqualTo(ProcessingStatus.PROCESSING);
            assertThat(redisTemplate.opsForStream().pending(resultStream, consumerGroup, Range.unbounded(), 100))
                    .extracting("id").doesNotContain(first, second);
        } finally {
            mediaResultConsumer.start();
        }
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

        // The worker finishes after all: it writes its variants and reports, and core ignores
        // the report. The staging original is still there too (the worker deletes it only after
        // reporting, and this one pretends to have crashed first).
        String prefix = MediaProcessingService.pendingVariantPrefix(stuck.mediaId());
        s3Client.putObject(PutObjectRequest.builder().bucket(mediaBucket).key(prefix + "640.webp").build(),
                RequestBody.fromBytes(randomFile(64)));
        awaitAcknowledged(publishResult(okResult(stuck, sha256(stuck.file()))));
        assertThat(articleMediaRepository.findById(stuck.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(stagingObjectExists(stuck.mediaId())).isTrue();

        // Inside the grace period nothing is deleted; past it, the row and every object go.
        assertThat(mediaCleanupJob.sweepFailed(Instant.now().minus(Duration.ofDays(1)))).isZero();
        assertThat(articleMediaRepository.existsById(stuck.mediaId())).isTrue();
        assertThat(mediaCleanupJob.sweepFailed(Instant.now().plusSeconds(1))).isPositive();
        assertThat(articleMediaRepository.existsById(stuck.mediaId())).isFalse();
        assertThat(mediaObjectExists(prefix + "640.webp")).isFalse();
        assertThat(stagingObjectExists(stuck.mediaId())).isFalse();
    }
}

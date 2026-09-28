package com.persiangulfwiki.core.media;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaDescription;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.pipeline.MediaResultConsumer;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaDescriptionRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationDecision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
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
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Metadata edits and panorama hotspots, against real Postgres, Redis and MinIO: proposing a new
// metadata version, its moderation (including the forward-only ordering rule and the pending
// versions an outcome closes), hotspot validation, who may propose and read what, the edit
// limits, and version-number allocation under concurrency.
//
// Published items are seeded as rows (their bytes are irrelevant: a later approval never touches
// files). The one flow that needs a real file -- an edit proposed before processing finishes,
// whose approval is then the item's first -- drives a real upload and a real pipeline result, as
// MediaProcessingFlowIntegrationTests does.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MediaMetadataEditFlowIntegrationTests {

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
    private MediaDescriptionRepository mediaDescriptionRepository;

    @Autowired
    private PanoramaLinkRepository panoramaLinkRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ModerationDecisionRepository moderationDecisionRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private S3Client s3Client;

    @Value("${app.storage.media-bucket}")
    private String mediaBucket;

    @Value("${app.media.job-stream}")
    private String jobStream;

    @Value("${app.media.result-stream}")
    private String resultStream;

    @Value("${app.media.max-pending-edits-per-user}")
    private int maxPendingEdits;

    @Value("${app.media.metadata-edits-per-window}")
    private int editsPerWindow;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    private Account verifiedAccount(boolean isModerator) throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "edit-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("edt_" + slug, email, PASSWORD))))
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

    private UUID createDraftArticle(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                "edit-" + UUID.randomUUID(), "Title", objectMapper.readTree("{\"text\":\"body\"}"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private UUID createPublicArticle(Account author) throws Exception {
        UUID articleId = createDraftArticle(author);
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(author.accessCookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        articleRevisionService.applyModerationOutcome(revisionId, RevisionStatus.APPROVED);
        return articleId;
    }

    // --- Request helpers ---

    private static Map<String, Object> metadata(int headingDeg, Map<String, String> descriptions,
            List<Map<String, Object>> links) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("headingDeg", headingDeg);
        metadata.put("descriptions", descriptions);
        metadata.put("links", links);
        return metadata;
    }

    private static Map<String, Object> link(UUID toMediaId, Object yawDeg, Object pitchDeg, String label) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("toMediaId", toMediaId);
        link.put("yawDeg", yawDeg);
        link.put("pitchDeg", pitchDeg);
        link.put("label", label);
        return link;
    }

    private static Map<String, Object> link(UUID toMediaId) {
        return link(toMediaId, 90, 0, "next");
    }

    private ResultActions propose(Account caller, UUID articleId, UUID mediaId, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/api/articles/" + articleId + "/media/" + mediaId + "/metadata-versions")
                .cookie(caller.accessCookie())
                .with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID proposeVersion(Account caller, UUID articleId, UUID mediaId, int headingDeg) throws Exception {
        String body = propose(caller, articleId, mediaId, metadata(headingDeg, Map.of(), List.of()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.metadata.versionId"));
    }

    private ModerationTask taskOf(UUID versionId) {
        return moderationTaskRepository.findByMediaMetadataVersionId(versionId).orElseThrow();
    }

    private ResultActions decide(Account moderator, UUID taskId, Decision decision, String reason) throws Exception {
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderator.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk());
        return mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                .cookie(moderator.accessCookie())
                .with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DecideRequest(decision, reason))));
    }

    private MetadataVersionStatus statusOf(UUID versionId) {
        return mediaMetadataVersionRepository.findById(versionId).orElseThrow().getStatus();
    }

    // Asserts the task was closed as a side effect of another version's decision: DECIDED, with a
    // single REJECT recorded against the deciding moderator and an explanatory note.
    private void assertClosedBy(UUID versionId, Account moderator) {
        ModerationTask task = taskOf(versionId);
        assertThat(task.getState()).isEqualTo(ModerationTaskState.DECIDED);
        List<ModerationDecision> decisions = moderationDecisionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());
        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.getDecision()).isEqualTo(Decision.REJECT);
            assertThat(decision.getModeratorId()).isEqualTo(moderator.userId());
            assertThat(decision.getReason()).isNotBlank();
        });
    }

    // --- Seeding helpers: rows only, no bytes ---

    private ArticleMedia seedItem(UUID articleId, UUID uploaderId, MediaKind type, ProcessingStatus processing,
            PublicationStatus publication) {
        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(type)
                .uploadedBy(uploaderId)
                .declaredContentType("image/jpeg")
                .declaredBytes(1024)
                .declaredSha256(sha256Of(UUID.randomUUID()))
                .processingStatus(processing)
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .build());
        boolean isApproved = publication == PublicationStatus.PUBLISHED || publication == PublicationStatus.HIDDEN;
        if (processing == ProcessingStatus.READY) {
            String prefix = isApproved
                    ? MediaVariantService.publicVariantPrefix(media.getId())
                    : MediaProcessingService.pendingVariantPrefix(media.getId());
            media.setVariants(List.of(new MediaVariant("640", "webp", prefix + "640.webp", 512, 640, 320)));
            media.setPlaceholder(PLACEHOLDER);
        }
        MetadataVersionStatus firstStatus = switch (publication) {
            case PUBLISHED, HIDDEN -> MetadataVersionStatus.APPROVED;
            case REJECTED -> MetadataVersionStatus.REJECTED;
            case PENDING -> MetadataVersionStatus.PENDING_REVIEW;
        };
        MediaMetadataVersion first = seedVersion(media.getId(), 1, uploaderId, firstStatus);
        mediaDescriptionRepository.save(MediaDescription.builder()
                .metadataVersionId(first.getId())
                .language("en")
                .text("Original caption")
                .build());
        if (isApproved) {
            media.setCurrentMetadataVersionId(first.getId());
            media.setPublishedAt(Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));
        }
        media.setPublicationStatus(publication);
        return articleMediaRepository.save(media);
    }

    private MediaMetadataVersion seedVersion(UUID mediaId, int number, UUID submittedBy, MetadataVersionStatus status) {
        return mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                .mediaId(mediaId)
                .versionNumber(number)
                .submittedBy(submittedBy)
                .status(status)
                .headingDeg(BigDecimal.valueOf(90))
                .build());
    }

    private ModerationTask seedTask(UUID versionId) {
        return moderationTaskRepository.save(ModerationTask.builder()
                .mediaMetadataVersionId(versionId)
                .state(ModerationTaskState.OPEN)
                .build());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Of(UUID seed) {
        try {
            return sha256(seed.toString().getBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // --- Real upload helpers: reserve -> PUT -> complete -> result ---

    private UUID uploadToProcessing(Account uploader, UUID articleId, byte[] file) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "IMAGE");
        body.put("contentType", "image/jpeg");
        body.put("bytes", file.length);
        body.put("sha256", sha256(file));
        body.put("metadata", Map.of("headingDeg", 10));
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
        return mediaId;
    }

    private String jobIdFor(UUID mediaId) {
        List<MapRecord<String, Object, Object>> jobs = redisTemplate.opsForStream().read(StreamOffset.fromStart(jobStream));
        return jobs.stream()
                .filter(record -> mediaId.toString().equals(record.getValue().get("submission_id")))
                .map(record -> (String) record.getValue().get("job_id"))
                .findFirst().orElseThrow();
    }

    // Plays the worker: stores the variant and reports it on the results stream.
    private void processToReady(UUID mediaId, byte[] file) throws Exception {
        byte[] variant = randomBytes(256);
        String key = MediaProcessingService.pendingVariantPrefix(mediaId) + "640.webp";
        s3Client.putObject(PutObjectRequest.builder().bucket(mediaBucket).key(key).contentType("image/webp").build(),
                RequestBody.fromBytes(variant));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("job_id", jobIdFor(mediaId));
        result.put("submission_id", mediaId.toString());
        result.put("type", "media.image");
        result.put("status", "ok");
        result.put("attempt", 1);
        result.put("media", Map.of(
                "sha256", sha256(file),
                "width", 4000,
                "height", 3000,
                "placeholder", PLACEHOLDER,
                "variants", List.of(Map.of("size", "640", "format", "webp", "key", key,
                        "bytes", variant.length, "width", 640, "height", 480))));
        redisTemplate.opsForStream().add(StreamRecords.string(
                Map.of(MediaResultConsumer.RESULT_FIELD, objectMapper.writeValueAsString(result))).withStreamKey(resultStream));
        await().atMost(APPLIED_WITHIN).until(() ->
                articleMediaRepository.findById(mediaId).orElseThrow().getProcessingStatus() == ProcessingStatus.READY);
    }

    // --- Smoke: propose -> review against the current version -> approve / reject ---

    @Test
    void proposedEditIsReviewedAgainstTheCurrentVersionAndOnlyApprovalChangesThePublicView() throws Exception {
        Account uploader = verifiedAccount(false);
        Account stranger = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        ArticleMedia item = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED);
        UUID mediaId = item.getId();
        String itemUrl = "/api/articles/" + articleId + "/media/" + mediaId;

        // A complete replacement: the new version has exactly what was sent, no copied caption.
        String proposed = propose(uploader, articleId, mediaId, metadata(200,
                Map.of("fa", "عنوان ویرایش‌شده"), List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.metadata.versionNumber").value(2))
                .andExpect(jsonPath("$.data.metadata.headingDeg").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.submittedBy").value(uploader.userId().toString()))
                .andExpect(jsonPath("$.data.descriptions.fa").value("عنوان ویرایش‌شده"))
                .andExpect(jsonPath("$.data.descriptions.en").doesNotExist())
                .andExpect(jsonPath("$.data.links", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        UUID editId = UUID.fromString(JsonPath.read(proposed, "$.data.metadata.versionId"));

        // The item is READY, so the task opened with the proposal, not later.
        ModerationTask task = taskOf(editId);
        assertThat(task.getState()).isEqualTo(ModerationTaskState.OPEN);

        // Nothing public moved.
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data[0].metadata.headingDeg").value(90))
                .andExpect(jsonPath("$.data[0].description").value("Original caption"));

        // The review shows the proposal next to the current version it would replace.
        mockMvc.perform(get("/api/moderation/tasks/" + task.getId()).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaReview.proposed.versionNumber").value(2))
                .andExpect(jsonPath("$.data.mediaReview.proposed.headingDeg").value(200))
                .andExpect(jsonPath("$.data.mediaReview.descriptions.fa").value("عنوان ویرایش‌شده"))
                .andExpect(jsonPath("$.data.mediaReview.item.metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data.mediaReview.current.metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data.mediaReview.current.metadata.headingDeg").value(90))
                .andExpect(jsonPath("$.data.mediaReview.current.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.mediaReview.current.descriptions.en").value("Original caption"));

        decide(moderator, task.getId(), Decision.APPROVE, null).andExpect(status().isOk());

        ArticleMedia approved = articleMediaRepository.findById(mediaId).orElseThrow();
        assertThat(approved.getCurrentMetadataVersionId()).isEqualTo(editId);
        assertThat(approved.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(approved.getPublishedAt()).isEqualTo(item.getPublishedAt());
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("language", "fa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].metadata.versionNumber").value(2))
                .andExpect(jsonPath("$.data[0].metadata.headingDeg").value(200))
                .andExpect(jsonPath("$.data[0].description").value("عنوان ویرایش‌شده"));
        mockMvc.perform(get(itemUrl).param("language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.versionNumber").value(2))
                .andExpect(jsonPath("$.data.description").value(nullValue()));

        // A rejected edit changes nothing public.
        UUID rejectedId = proposeVersion(uploader, articleId, mediaId, 10);
        decide(moderator, taskOf(rejectedId).getId(), Decision.REJECT, "wrong heading").andExpect(status().isOk());
        assertThat(statusOf(rejectedId)).isEqualTo(MetadataVersionStatus.REJECTED);
        assertThat(articleMediaRepository.findById(mediaId).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(editId);
        mockMvc.perform(get(itemUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.versionNumber").value(2))
                .andExpect(jsonPath("$.data.metadata.headingDeg").value(200));

        // History: the uploader sees everything, everyone else only what was approved.
        mockMvc.perform(get(itemUrl + "/metadata-versions").cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].descriptions.en").value("Original caption"))
                .andExpect(jsonPath("$.data[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[2].status").value("REJECTED"));
        mockMvc.perform(get(itemUrl + "/metadata-versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].metadata.versionNumber").value(2));
        mockMvc.perform(get(itemUrl + "/metadata-versions").cookie(stranger.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    // --- An edit on an item whose file is still processing waits for READY ---

    @Test
    void editOnAnUnprocessedItemIsQueuedAtReadyAndItsApprovalPublishesTheItem() throws Exception {
        Account uploader = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        byte[] file = randomBytes(2048);
        UUID mediaId = uploadToProcessing(uploader, articleId, file);
        UUID firstId = mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(mediaId)
                .orElseThrow().getId();

        UUID editId = proposeVersion(uploader, articleId, mediaId, 45);
        assertThat(moderationTaskRepository.existsByMediaMetadataVersionId(firstId)).isFalse();
        assertThat(moderationTaskRepository.existsByMediaMetadataVersionId(editId)).isFalse();

        processToReady(mediaId, file);
        await().atMost(APPLIED_WITHIN).until(() -> moderationTaskRepository.existsByMediaMetadataVersionId(firstId)
                && moderationTaskRepository.existsByMediaMetadataVersionId(editId));

        // Approving the edit first is the item's first approval: it publishes the item with the
        // edit as current, and closes the older upload-time version and its task.
        decide(moderator, taskOf(editId).getId(), Decision.APPROVE, null).andExpect(status().isOk());
        ArticleMedia published = articleMediaRepository.findById(mediaId).orElseThrow();
        assertThat(published.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(published.getCurrentMetadataVersionId()).isEqualTo(editId);
        assertThat(published.getVariants()).extracting(MediaVariant::key)
                .containsExactly(MediaVariantService.publicVariantPrefix(mediaId) + "640.webp");
        assertThat(statusOf(firstId)).isEqualTo(MetadataVersionStatus.REJECTED);
        assertClosedBy(firstId, moderator);

        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + mediaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.versionNumber").value(2))
                .andExpect(jsonPath("$.data.metadata.headingDeg").value(45));
    }

    // --- Ordering: current only moves forward, and outcomes close what they make moot ---

    @Test
    void approvalOnlyMovesTheCurrentVersionForwardAndClosesOlderPendingEdits() throws Exception {
        Account uploader = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        UUID mediaId = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED).getId();

        UUID v2 = proposeVersion(uploader, articleId, mediaId, 20);
        UUID v3 = proposeVersion(uploader, articleId, mediaId, 30);
        UUID v4 = proposeVersion(uploader, articleId, mediaId, 40);

        decide(moderator, taskOf(v3).getId(), Decision.APPROVE, null).andExpect(status().isOk());
        assertThat(articleMediaRepository.findById(mediaId).orElseThrow().getCurrentMetadataVersionId()).isEqualTo(v3);
        // Older: closed, with its task. Newer: untouched, still an edit on top of v3.
        assertThat(statusOf(v2)).isEqualTo(MetadataVersionStatus.REJECTED);
        assertClosedBy(v2, moderator);
        assertThat(statusOf(v4)).isEqualTo(MetadataVersionStatus.PENDING_REVIEW);
        assertThat(taskOf(v4).getState()).isEqualTo(ModerationTaskState.OPEN);
        mockMvc.perform(post("/api/moderation/tasks/" + taskOf(v2).getId() + "/claim")
                        .cookie(moderator.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isConflict());

        // A pending version older than the current one (unreachable through the API now, so
        // seeded) can no longer become current -- but can still be rejected.
        UUID seededMediaId = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED).getId();
        MediaMetadataVersion stale = seedVersion(seededMediaId, 2, uploader.userId(), MetadataVersionStatus.PENDING_REVIEW);
        MediaMetadataVersion newer = seedVersion(seededMediaId, 3, uploader.userId(), MetadataVersionStatus.APPROVED);
        ArticleMedia seeded = articleMediaRepository.findById(seededMediaId).orElseThrow();
        seeded.setCurrentMetadataVersionId(newer.getId());
        articleMediaRepository.save(seeded);
        UUID staleTaskId = seedTask(stale.getId()).getId();
        decide(moderator, staleTaskId, Decision.APPROVE, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METADATA_VERSION_SUPERSEDED"));
        assertThat(articleMediaRepository.findById(seededMediaId).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(newer.getId());
        assertThat(statusOf(stale.getId())).isEqualTo(MetadataVersionStatus.PENDING_REVIEW);
        mockMvc.perform(post("/api/moderation/tasks/" + staleTaskId + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.REJECT, "outdated"))))
                .andExpect(status().isOk());
        assertThat(articleMediaRepository.findById(seededMediaId).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(newer.getId());
    }

    @Test
    void rejectingTheFirstVersionRejectsTheItemAndClosesEveryOtherPendingVersion() throws Exception {
        Account uploader = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        ArticleMedia item = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PENDING);
        UUID firstId = mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(item.getId())
                .orElseThrow().getId();
        UUID firstTaskId = seedTask(firstId).getId();
        UUID editId = proposeVersion(uploader, articleId, item.getId(), 60);

        decide(moderator, firstTaskId, Decision.REJECT, "off topic").andExpect(status().isOk());
        assertThat(articleMediaRepository.findById(item.getId()).orElseThrow().getPublicationStatus())
                .isEqualTo(PublicationStatus.REJECTED);
        assertThat(statusOf(editId)).isEqualTo(MetadataVersionStatus.REJECTED);
        assertClosedBy(editId, moderator);

        // A pending version left on a rejected item (seeded: the API no longer leaves one) can
        // never publish it.
        ArticleMedia rejected = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.REJECTED);
        MediaMetadataVersion leftover = seedVersion(rejected.getId(), 2, uploader.userId(),
                MetadataVersionStatus.PENDING_REVIEW);
        decide(moderator, seedTask(leftover.getId()).getId(), Decision.APPROVE, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_ALREADY_REJECTED"));
        ArticleMedia unchanged = articleMediaRepository.findById(rejected.getId()).orElseThrow();
        assertThat(unchanged.getPublicationStatus()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(unchanged.getCurrentMetadataVersionId()).isNull();

        // Rejecting a later edit of a never-approved item rejects only that edit.
        ArticleMedia pending = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PENDING);
        UUID pendingEditId = proposeVersion(uploader, articleId, pending.getId(), 70);
        decide(moderator, taskOf(pendingEditId).getId(), Decision.REJECT, "no").andExpect(status().isOk());
        assertThat(articleMediaRepository.findById(pending.getId()).orElseThrow().getPublicationStatus())
                .isEqualTo(PublicationStatus.PENDING);
    }

    // --- Panorama links ---

    @Test
    void panoramaLinksAreValidatedAgainstEveryRule() throws Exception {
        Account uploader = verifiedAccount(false);
        Account other = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        UUID otherArticleId = createPublicArticle(other);
        UUID draftArticleId = createDraftArticle(other);

        ArticleMedia source = seedItem(articleId, uploader.userId(), MediaKind.PANORAMA_360, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED);
        ArticleMedia crossArticle = seedItem(otherArticleId, other.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED);
        ArticleMedia image = seedItem(otherArticleId, other.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED);
        ArticleMedia othersPending = seedItem(otherArticleId, other.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PENDING);
        ArticleMedia onDraftArticle = seedItem(draftArticleId, other.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED);
        ArticleMedia ownPending = seedItem(articleId, uploader.userId(), MediaKind.PANORAMA_360, ProcessingStatus.READY,
                PublicationStatus.PENDING);
        ArticleMedia imageSource = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED);
        UUID sourceId = source.getId();

        // Accepted: a public panorama in another article, and the caller's own pending one. The
        // own one is shown back to them, without a preview.
        String created = propose(uploader, articleId, sourceId, metadata(0, Map.of(),
                List.of(link(crossArticle.getId(), 90, -5, "harbour"), link(ownPending.getId(), 180.25, 10, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.links", hasSize(2)))
                .andExpect(jsonPath("$.data.links[0].target.mediaId").value(crossArticle.getId().toString()))
                .andExpect(jsonPath("$.data.links[0].target.articleId").value(otherArticleId.toString()))
                .andExpect(jsonPath("$.data.links[0].target.smallestVariant.size").value("640"))
                .andExpect(jsonPath("$.data.links[0].label").value("harbour"))
                .andExpect(jsonPath("$.data.links[1].target.mediaId").value(ownPending.getId().toString()))
                .andExpect(jsonPath("$.data.links[1].yawDeg").value(180.25))
                .andExpect(jsonPath("$.data.links[1].target.smallestVariant").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        UUID linkedVersionId = UUID.fromString(JsonPath.read(created, "$.data.metadata.versionId"));
        assertThat(panoramaLinkRepository.findByMetadataVersionId(linkedVersionId)).hasSize(2);

        // 20 hotspots is the cap; 21 is refused.
        propose(uploader, articleId, sourceId, metadata(0, Map.of(), Collections.nCopies(20, link(crossArticle.getId()))))
                .andExpect(status().isCreated());
        propose(uploader, articleId, sourceId, metadata(0, Map.of(), Collections.nCopies(21, link(crossArticle.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // The rules that need a lookup, each with its own code.
        propose(uploader, articleId, sourceId, metadata(0, Map.of(), List.of(link(sourceId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_PANORAMA_LINK"));
        propose(uploader, articleId, sourceId, metadata(0, Map.of(), List.of(link(image.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PANORAMA_LINK_TARGET_NOT_PANORAMA"));
        propose(uploader, articleId, imageSource.getId(), metadata(0, Map.of(), List.of(link(crossArticle.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PANORAMA_LINKS_NOT_SUPPORTED"));
        // Missing, someone else's pending item, and one on an article the caller cannot read all
        // look the same -- and an unseeable non-panorama is still a 404, not a type complaint.
        ArticleMedia othersPendingImage = seedItem(otherArticleId, other.userId(), MediaKind.IMAGE,
                ProcessingStatus.READY, PublicationStatus.PENDING);
        for (UUID unseeable : List.of(UUID.randomUUID(), othersPending.getId(), onDraftArticle.getId(),
                othersPendingImage.getId())) {
            propose(uploader, articleId, sourceId, metadata(0, Map.of(), List.of(link(unseeable))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PANORAMA_LINK_TARGET_NOT_FOUND"));
        }

        // Field-level shape.
        for (Map<String, Object> bad : List.of(link(crossArticle.getId(), 360, 0, null),
                link(crossArticle.getId(), -1, 0, null), link(crossArticle.getId(), 359.999, 0, null),
                link(crossArticle.getId(), 90, 90.5, null), link(crossArticle.getId(), 90, -91, null),
                link(crossArticle.getId(), 90, 0, "x".repeat(201)), link(null, 90, 0, null))) {
            propose(uploader, articleId, sourceId, metadata(0, Map.of(), List.of(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        // A moderator may link to anything that exists.
        propose(moderator, articleId, sourceId, metadata(0, Map.of(),
                List.of(link(othersPending.getId()), link(onDraftArticle.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.links", hasSize(2)));

        // Hiding or rejecting a target never deletes the links to it.
        crossArticle.setPublicationStatus(PublicationStatus.HIDDEN);
        articleMediaRepository.save(crossArticle);
        ownPending.setPublicationStatus(PublicationStatus.REJECTED);
        articleMediaRepository.save(ownPending);
        assertThat(panoramaLinkRepository.findByMetadataVersionId(linkedVersionId)).hasSize(2);

        // Reservation takes hotspots for a panorama's first version, under the same rules.
        Map<String, Object> reserve = new LinkedHashMap<>();
        reserve.put("type", "PANORAMA_360");
        reserve.put("contentType", "image/jpeg");
        reserve.put("bytes", 4096);
        reserve.put("sha256", sha256(randomBytes(64)));
        ArticleMedia publicTarget = seedItem(otherArticleId, other.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED);
        reserve.put("metadata", metadata(0, Map.of(), List.of(link(publicTarget.getId()))));
        String reserved = mockMvc.perform(post("/api/articles/" + articleId + "/media")
                        .cookie(uploader.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reserve)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID reservedId = UUID.fromString(JsonPath.read(reserved, "$.data.mediaId"));
        UUID reservedFirst = mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(reservedId)
                .orElseThrow().getId();
        assertThat(panoramaLinkRepository.findByMetadataVersionId(reservedFirst)).singleElement()
                .satisfies(stored -> assertThat(stored.getToMediaId()).isEqualTo(publicTarget.getId()));

        reserve.put("sha256", sha256(randomBytes(64)));
        reserve.put("metadata", metadata(0, Map.of(), List.of(link(othersPending.getId()))));
        mockMvc.perform(post("/api/articles/" + articleId + "/media")
                        .cookie(uploader.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reserve)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PANORAMA_LINK_TARGET_NOT_FOUND"));
        reserve.put("type", "IMAGE");
        reserve.put("metadata", metadata(0, Map.of(), List.of(link(publicTarget.getId()))));
        mockMvc.perform(post("/api/articles/" + articleId + "/media")
                        .cookie(uploader.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reserve)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PANORAMA_LINKS_NOT_SUPPORTED"));
    }

    // --- Authorization and editable states ---

    @Test
    void onlyTheUploaderAndModeratorsMayProposeAndOnlyWhileTheItemCanShowIt() throws Exception {
        Account uploader = verifiedAccount(false);
        Account stranger = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        UUID otherArticleId = createPublicArticle(uploader);
        ArticleMedia published = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED);
        ArticleMedia pending = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PENDING);
        String publishedVersions = "/api/articles/" + articleId + "/media/" + published.getId() + "/metadata-versions";
        String body = objectMapper.writeValueAsString(metadata(15, Map.of(), List.of()));

        mockMvc.perform(post(publishedVersions).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(publishedVersions).cookie(uploader.accessCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        propose(stranger, articleId, published.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEDIA_UPLOADER"));
        propose(stranger, articleId, pending.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        propose(uploader, otherArticleId, published.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isNotFound());
        propose(uploader, articleId, published.getId(), Map.of("headingDeg", 12, "shotAtOffset", "+03:30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_MEDIA_METADATA"));
        propose(uploader, articleId, published.getId(), Map.of("headingDeg", 359.999))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // No body at all is a malformed request, not an empty version.
        mockMvc.perform(post(publishedVersions).cookie(uploader.accessCookie()).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        propose(moderator, articleId, published.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submittedBy").value(moderator.userId().toString()));
        propose(uploader, articleId, pending.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isCreated());
        ArticleMedia processing = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.PROCESSING,
                PublicationStatus.PENDING);
        propose(uploader, articleId, processing.getId(), metadata(15, Map.of(), List.of()))
                .andExpect(status().isCreated());

        record State(ProcessingStatus processing, PublicationStatus publication) {
        }
        for (State state : List.of(new State(ProcessingStatus.UPLOADING, PublicationStatus.PENDING),
                new State(ProcessingStatus.FAILED, PublicationStatus.PENDING),
                new State(ProcessingStatus.READY, PublicationStatus.REJECTED),
                new State(ProcessingStatus.READY, PublicationStatus.HIDDEN))) {
            ArticleMedia refused = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, state.processing(),
                    state.publication());
            propose(uploader, articleId, refused.getId(), metadata(15, Map.of(), List.of()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MEDIA_NOT_EDITABLE"));
        }

        // History: the moderator's pending edit is visible to the uploader and moderators only.
        mockMvc.perform(get(publishedVersions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"));
        mockMvc.perform(get(publishedVersions).cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data[1].submittedBy").value(moderator.userId().toString()));
        String pendingVersions = "/api/articles/" + articleId + "/media/" + pending.getId() + "/metadata-versions";
        mockMvc.perform(get(pendingVersions)).andExpect(status().isNotFound());
        mockMvc.perform(get(pendingVersions).cookie(stranger.accessCookie())).andExpect(status().isNotFound());
        mockMvc.perform(get(pendingVersions).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        mockMvc.perform(get("/api/articles/" + otherArticleId + "/media/" + published.getId() + "/metadata-versions"))
                .andExpect(status().isNotFound());
    }

    // --- Anti-abuse limits ---

    @Test
    void editLimitsCapPendingEditsAndRateButSpareModerators() throws Exception {
        Account capped = verifiedAccount(false);
        Account busy = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(capped);
        UUID cappedItem = seedItem(articleId, capped.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED).getId();
        for (int i = 0; i < maxPendingEdits; i++) {
            seedVersion(cappedItem, i + 2, capped.userId(), MetadataVersionStatus.PENDING_REVIEW);
        }
        propose(capped, articleId, cappedItem, metadata(1, Map.of(), List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENDING_METADATA_EDIT_LIMIT_EXCEEDED"));

        UUID busyItem = seedItem(articleId, busy.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED).getId();
        for (int i = 0; i < editsPerWindow; i++) {
            proposeVersion(busy, articleId, busyItem, i);
        }
        propose(busy, articleId, busyItem, metadata(1, Map.of(), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("METADATA_EDIT_RATE_LIMITED"));

        // A moderator past both numbers is still let through.
        for (int i = 0; i < maxPendingEdits; i++) {
            seedVersion(busyItem, editsPerWindow + i + 2, moderator.userId(), MetadataVersionStatus.PENDING_REVIEW);
        }
        for (int i = 0; i <= editsPerWindow; i++) {
            proposeVersion(moderator, articleId, busyItem, i);
        }
    }

    // --- Concurrency: version numbers never collide into a 500 ---

    @Test
    void concurrentEditsOfOneItemGetDistinctVersionNumbers() throws Exception {
        Account uploader = verifiedAccount(false);
        UUID articleId = createPublicArticle(uploader);
        UUID mediaId = seedItem(articleId, uploader.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED).getId();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                int headingDeg = i;
                statuses.add(pool.submit(() -> {
                    start.await();
                    return propose(uploader, articleId, mediaId, metadata(headingDeg, Map.of(), List.of()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : statuses) {
                assertThat(result.get()).isEqualTo(201);
            }
        }
        assertThat(mediaMetadataVersionRepository.findByMediaIdOrderByVersionNumberAsc(mediaId))
                .extracting(MediaMetadataVersion::getVersionNumber)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(moderationTaskRepository.findAll().stream()
                .filter(task -> task.getMediaMetadataVersionId() != null)
                .map(ModerationTask::getMediaMetadataVersionId)
                .filter(versionId -> mediaMetadataVersionRepository.findById(versionId)
                        .map(version -> version.getMediaId().equals(mediaId)).orElse(false))
                .count()).isEqualTo(writers);
    }
}

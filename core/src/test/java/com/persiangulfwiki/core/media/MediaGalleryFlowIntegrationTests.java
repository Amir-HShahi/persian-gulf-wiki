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
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.PanoramaLink;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.pipeline.MediaResultConsumer;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
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
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The gallery's read path and its moderation side, against real Postgres, Redis and MinIO: the
// public list, the single-item read with signed URLs for a not-yet-public item, the moderator's
// task detail, and approval copying the files to their public keys.
//
// The headline flow drives a real upload to READY (the worker's result written onto the results
// stream by hand, as in MediaProcessingFlowIntegrationTests) with real variant objects in the
// media bucket, so both the signed and the public URLs point at bytes that exist. The link and
// paging cases seed rows directly: they are about which rows are shown, not about bytes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MediaGalleryFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Random RANDOM = new Random();
    private static final Duration APPLIED_WITHIN = Duration.ofSeconds(15);
    private static final String PLACEHOLDER = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

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
    private PanoramaLinkRepository panoramaLinkRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private S3Client s3Client;

    @Value("${app.storage.media-bucket}")
    private String mediaBucket;

    @Value("${app.storage.public-base-url}")
    private String publicBaseUrl;

    @Value("${app.media.job-stream}")
    private String jobStream;

    @Value("${app.media.result-stream}")
    private String resultStream;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    private Account verifiedAccount(boolean isModerator) throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "gallery-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("gal_" + slug, email, PASSWORD))))
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

    // A draft: visible only to its author and moderators.
    private UUID createDraftArticle(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                "gallery-" + UUID.randomUUID(), "Title", objectMapper.readTree("{\"text\":\"body\"}"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    // Readable by everyone: its first revision approved through the moderation outcome writer.
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

    // --- Upload helpers: reserve -> PUT -> complete -> result, as in the processing tests ---

    private static String sha256(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private UUID uploadToProcessing(Account uploader, UUID articleId, byte[] file) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("shotAt", "2026-04-12T06:30:00Z");
        metadata.put("shotAtOffset", "+03:30");
        metadata.put("location", Map.of("latitude", 26.5667, "longitude", 56.25));
        metadata.put("headingDeg", 135);
        metadata.put("headingRef", "TRUE");
        metadata.put("descriptions", Map.of("en", "Sunrise over the strait", "fa", "طلوع بر فراز تنگه"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "IMAGE");
        body.put("contentType", "image/jpeg");
        body.put("bytes", file.length);
        body.put("sha256", sha256(file));
        body.put("metadata", metadata);
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

    // Plays the worker: writes the variant object (unless told not to) and reports it.
    private void processToReady(UUID mediaId, byte[] file, byte[] variantBytes, boolean isVariantStored)
            throws Exception {
        String key = MediaProcessingService.pendingVariantPrefix(mediaId) + "640.webp";
        if (isVariantStored) {
            s3Client.putObject(PutObjectRequest.builder().bucket(mediaBucket).key(key).contentType("image/webp").build(),
                    RequestBody.fromBytes(variantBytes));
        }
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
                        "bytes", variantBytes.length, "width", 640, "height", 480))));
        redisTemplate.opsForStream().add(StreamRecords.string(
                Map.of(MediaResultConsumer.RESULT_FIELD, objectMapper.writeValueAsString(result))).withStreamKey(resultStream));
        await().atMost(APPLIED_WITHIN).until(() ->
                articleMediaRepository.findById(mediaId).orElseThrow().getProcessingStatus() == ProcessingStatus.READY);
    }

    private ModerationTask taskFor(UUID mediaId) {
        UUID versionId = mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(mediaId)
                .orElseThrow().getId();
        await().atMost(APPLIED_WITHIN).until(() -> moderationTaskRepository.findAll().stream()
                .anyMatch(task -> versionId.equals(task.getMediaMetadataVersionId())));
        return moderationTaskRepository.findAll().stream()
                .filter(task -> versionId.equals(task.getMediaMetadataVersionId()))
                .findFirst().orElseThrow();
    }

    private void claim(Account moderator, UUID taskId) throws Exception {
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderator.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk());
    }

    private HeadObjectResponse headMedia(String key) {
        return s3Client.headObject(builder -> builder.bucket(mediaBucket).key(key));
    }

    // --- Seeding helpers: rows only, no bytes ---

    private ArticleMedia seedItem(UUID articleId, UUID uploaderId, MediaKind type, ProcessingStatus processing,
            PublicationStatus publication, Instant publishedAt, String... variantSizes) {
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
        // Where the real flow leaves the files: moved to the public prefix by the first approval.
        String prefix = isApproved
                ? MediaVariantService.publicVariantPrefix(media.getId())
                : MediaProcessingService.pendingVariantPrefix(media.getId());
        List<MediaVariant> variants = new ArrayList<>();
        int width = 2048;
        for (String size : variantSizes) {
            variants.add(new MediaVariant(size, "webp", prefix + size + ".webp", width * 100L, width, width / 2));
            width *= 2;
        }
        media.setVariants(variants);
        media.setPlaceholder(PLACEHOLDER);
        MediaMetadataVersion first = seedVersion(media.getId(), 1, uploaderId,
                isApproved ? MetadataVersionStatus.APPROVED : MetadataVersionStatus.PENDING_REVIEW);
        if (isApproved) {
            media.setCurrentMetadataVersionId(first.getId());
            media.setPublishedAt(publishedAt);
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

    private void seedLink(UUID versionId, UUID targetId, int yawDeg) {
        panoramaLinkRepository.save(PanoramaLink.builder()
                .metadataVersionId(versionId)
                .toMediaId(targetId)
                .yawDeg(BigDecimal.valueOf(yawDeg))
                .pitchDeg(BigDecimal.ZERO)
                .label("to " + yawDeg)
                .build());
    }

    private static String sha256Of(UUID seed) {
        try {
            return sha256(seed.toString().getBytes());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // --- Smoke: upload -> signed URLs while pending -> moderator review -> approve -> public ---

    @Test
    void pendingItemIsServedThroughSignedUrlsAndApprovalPublishesItToThePublicGallery() throws Exception {
        Account uploader = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        byte[] file = randomBytes(2048);
        byte[] variant = randomBytes(512);
        UUID mediaId = uploadToProcessing(uploader, articleId, file);
        processToReady(mediaId, file, variant, true);

        // Not public yet: absent from the gallery, and a 404 to anyone but its uploader and
        // moderators.
        mockMvc.perform(get("/api/articles/" + articleId + "/media"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + mediaId))
                .andExpect(status().isNotFound());

        // The uploader gets a signed URL for the pending variant, on a response nothing may cache.
        String pending = mockMvc.perform(get("/api/articles/" + articleId + "/media/" + mediaId)
                        .param("language", "en")
                        .cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.data.processingStatus").value("READY"))
                .andExpect(jsonPath("$.data.publicationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.placeholder").value(PLACEHOLDER))
                .andExpect(jsonPath("$.data.variants", hasSize(1)))
                .andExpect(jsonPath("$.data.variants[0].url", containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.data.metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data.metadata.location.latitude").value(26.5667))
                .andExpect(jsonPath("$.data.metadata.location.longitude").value(56.25))
                .andExpect(jsonPath("$.data.metadata.shotAtOffset").value("+03:30"))
                .andExpect(jsonPath("$.data.description").value("Sunrise over the strait"))
                .andExpect(jsonPath("$.data.publishedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        HttpResponse<byte[]> signedGet = HTTP.send(
                HttpRequest.newBuilder(URI.create(JsonPath.read(pending, "$.data.variants[0].url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(signedGet.statusCode()).isEqualTo(200);
        assertThat(signedGet.body()).isEqualTo(variant);
        assertThat(signedGet.headers().firstValue("Cache-Control")).hasValue("private, no-store");

        // The moderator reaches the item and the version under review through the task.
        ModerationTask task = taskFor(mediaId);
        mockMvc.perform(get("/api/moderation/tasks/" + task.getId()).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.data.task.id").value(task.getId().toString()))
                .andExpect(jsonPath("$.data.task.state").value("OPEN"))
                .andExpect(jsonPath("$.data.mediaReview.item.id").value(mediaId.toString()))
                .andExpect(jsonPath("$.data.mediaReview.item.variants[0].url", containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.data.mediaReview.proposed.versionId")
                        .value(task.getMediaMetadataVersionId().toString()))
                .andExpect(jsonPath("$.data.mediaReview.proposedStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.mediaReview.submittedBy").value(uploader.userId().toString()))
                .andExpect(jsonPath("$.data.mediaReview.descriptions.en").value("Sunrise over the strait"))
                .andExpect(jsonPath("$.data.mediaReview.descriptions.fa").value("طلوع بر فراز تنگه"))
                .andExpect(jsonPath("$.data.mediaReview.links", hasSize(0)))
                // Never approved: nothing current to compare against.
                .andExpect(jsonPath("$.data.mediaReview.current").value(nullValue()));

        claim(moderator, task.getId());
        mockMvc.perform(post("/api/moderation/tasks/" + task.getId() + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null))))
                .andExpect(status().isOk());

        // Approval moved the variant to its public key, served as immutable with its own type,
        // and the row now points there. The pending original is gone: a published file is
        // stored once.
        String publicKey = "public/" + mediaId + "/640.webp";
        HeadObjectResponse moved = headMedia(publicKey);
        assertThat(moved.cacheControl()).isEqualTo(IMMUTABLE);
        assertThat(moved.contentType()).isEqualTo("image/webp");
        assertThat(moved.contentLength()).isEqualTo(variant.length);
        assertThat(articleMediaRepository.findById(mediaId).orElseThrow().getVariants())
                .extracting(MediaVariant::key).containsExactly(publicKey);
        assertThatThrownBy(() -> headMedia(MediaProcessingService.pendingVariantPrefix(mediaId) + "640.webp"))
                .isInstanceOf(NoSuchKeyException.class);

        String publicUrl = publicBaseUrl + "/" + mediaId + "/640.webp";
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("language", "fa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(mediaId.toString()))
                .andExpect(jsonPath("$.data[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.data[0].processingStatus").value("READY"))
                .andExpect(jsonPath("$.data[0].publicationStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].variants[0].url").value(publicUrl))
                .andExpect(jsonPath("$.data[0].variants[0].width").value(640))
                .andExpect(jsonPath("$.data[0].metadata.headingDeg").value(135))
                .andExpect(jsonPath("$.data[0].metadata.headingRef").value("TRUE"))
                .andExpect(jsonPath("$.data[0].description").value("طلوع بر فراز تنگه"))
                .andExpect(jsonPath("$.data[0].links", hasSize(0)))
                .andExpect(jsonPath("$.data[0].publishedAt").isNotEmpty());
        // Anyone may now read it on its own, with the same public URL and no caption unasked.
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + mediaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants[0].url").value(publicUrl))
                .andExpect(jsonPath("$.data.description").value(nullValue()));
    }

    // Publishing a READY item whose file is gone from storage would publish dead URLs; the copy
    // runs before the approval is written, so the whole decision is refused (409) and rolled back.
    @Test
    void approvalIsRefusedWhenAVariantIsMissingFromStorage() throws Exception {
        Account uploader = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        byte[] file = randomBytes(2048);
        UUID mediaId = uploadToProcessing(uploader, articleId, file);
        processToReady(mediaId, file, randomBytes(256), false);

        ModerationTask task = taskFor(mediaId);
        claim(moderator, task.getId());
        mockMvc.perform(post("/api/moderation/tasks/" + task.getId() + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_FILES_MISSING"));

        ArticleMedia media = articleMediaRepository.findById(mediaId).orElseThrow();
        assertThat(media.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(media.getCurrentMetadataVersionId()).isNull();
        assertThat(moderationTaskRepository.findById(task.getId()).orElseThrow().getState())
                .isEqualTo(ModerationTaskState.CLAIMED);
        assertThatThrownBy(() -> headMedia("public/" + mediaId + "/640.webp")).isInstanceOf(NoSuchKeyException.class);
        assertThat(media.getVariants()).extracting(MediaVariant::key)
                .containsExactly(MediaProcessingService.pendingVariantPrefix(mediaId) + "640.webp");

        // Still decidable: the moderator can reject it instead.
        mockMvc.perform(post("/api/moderation/tasks/" + task.getId() + "/decide")
                        .cookie(moderator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.REJECT, "files lost"))))
                .andExpect(status().isOk());
        assertThat(articleMediaRepository.findById(mediaId).orElseThrow().getPublicationStatus())
                .isEqualTo(PublicationStatus.REJECTED);
    }

    // --- Panorama links: filtered at read time by the target's visibility ---

    @Test
    void panoramaLinksShowOnlyTargetsTheCallerMaySeeAndTheReviewShowsThemAll() throws Exception {
        Account uploader = verifiedAccount(false);
        Account draftAuthor = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createPublicArticle(uploader);
        UUID otherPublicArticleId = createPublicArticle(uploader);
        UUID draftArticleId = createDraftArticle(draftAuthor);
        Instant now = Instant.now();

        ArticleMedia source = seedItem(articleId, uploader.userId(), MediaKind.PANORAMA_360, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED, now, "2k", "4k");
        ArticleMedia elsewhere = seedItem(otherPublicArticleId, uploader.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED, now, "2k", "4k");
        ArticleMedia pending = seedItem(articleId, uploader.userId(), MediaKind.PANORAMA_360, ProcessingStatus.READY,
                PublicationStatus.PENDING, now, "2k");
        ArticleMedia hidden = seedItem(articleId, uploader.userId(), MediaKind.PANORAMA_360, ProcessingStatus.READY,
                PublicationStatus.HIDDEN, now, "2k");
        ArticleMedia onDraftArticle = seedItem(draftArticleId, draftAuthor.userId(), MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED, now, "2k");
        UUID current = source.getCurrentMetadataVersionId();
        seedLink(current, elsewhere.getId(), 90);
        seedLink(current, pending.getId(), 180);
        seedLink(current, hidden.getId(), 200);
        seedLink(current, onDraftArticle.getId(), 270);

        // Anonymous: only the public target on a public article, previewed by its smallest variant.
        mockMvc.perform(get("/api/articles/" + articleId + "/media"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(source.getId().toString()))
                .andExpect(jsonPath("$.data[0].metadata.headingDeg").value(90))
                .andExpect(jsonPath("$.data[0].links", hasSize(1)))
                .andExpect(jsonPath("$.data[0].links[0].yawDeg").value(90))
                .andExpect(jsonPath("$.data[0].links[0].label").value("to 90"))
                .andExpect(jsonPath("$.data[0].links[0].target.mediaId").value(elsewhere.getId().toString()))
                .andExpect(jsonPath("$.data[0].links[0].target.articleId").value(otherPublicArticleId.toString()))
                .andExpect(jsonPath("$.data[0].links[0].target.type").value("PANORAMA_360"))
                .andExpect(jsonPath("$.data[0].links[0].target.placeholder").value(PLACEHOLDER))
                .andExpect(jsonPath("$.data[0].links[0].target.smallestVariant.size").value("2k"))
                .andExpect(jsonPath("$.data[0].links[0].target.smallestVariant.url")
                        .value(publicBaseUrl + "/" + elsewhere.getId() + "/2k.webp"));

        // The draft article's author may see that article, so the link into it appears for them.
        mockMvc.perform(get("/api/articles/" + articleId + "/media").cookie(draftAuthor.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].links", hasSize(2)))
                .andExpect(jsonPath("$.data[0].links[1].target.mediaId").value(onDraftArticle.getId().toString()));

        // Links are kept, never deleted, while their target is hidden or pending.
        assertThat(panoramaLinkRepository.findByMetadataVersionId(current)).hasSize(4);

        // A pending edit with links to a public and a pending target: the review shows both, the
        // pending one without a preview.
        MediaMetadataVersion edit = seedVersion(source.getId(), 2, uploader.userId(), MetadataVersionStatus.PENDING_REVIEW);
        seedLink(edit.getId(), elsewhere.getId(), 45);
        seedLink(edit.getId(), pending.getId(), 300);
        UUID taskId = moderationTaskRepository.save(ModerationTask.builder()
                .mediaMetadataVersionId(edit.getId())
                .state(ModerationTaskState.OPEN)
                .build()).getId();
        mockMvc.perform(get("/api/moderation/tasks/" + taskId).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaReview.proposed.versionNumber").value(2))
                .andExpect(jsonPath("$.data.mediaReview.links", hasSize(2)))
                .andExpect(jsonPath("$.data.mediaReview.links[0].target.mediaId").value(elsewhere.getId().toString()))
                .andExpect(jsonPath("$.data.mediaReview.links[0].target.smallestVariant.size").value("2k"))
                .andExpect(jsonPath("$.data.mediaReview.links[1].target.mediaId").value(pending.getId().toString()))
                .andExpect(jsonPath("$.data.mediaReview.links[1].target.smallestVariant").value(nullValue()))
                // The item itself still shows its current version, not the edit.
                .andExpect(jsonPath("$.data.mediaReview.item.metadata.versionNumber").value(1))
                // ...and the current version is also there in full, every link included.
                .andExpect(jsonPath("$.data.mediaReview.current.metadata.versionNumber").value(1))
                .andExpect(jsonPath("$.data.mediaReview.current.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.mediaReview.current.links", hasSize(4)))
                .andExpect(jsonPath("$.data.mediaReview.item.variants[0].url")
                        .value(publicBaseUrl + "/" + source.getId() + "/2k.webp"));
    }

    // --- The list's paging, ordering and parameters ---

    @Test
    void galleryPagesNewestApprovalFirstAndRefusesBadParameters() throws Exception {
        Account author = verifiedAccount(false);
        UUID articleId = createPublicArticle(author);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ArticleMedia oldest = seedItem(articleId, author.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED, now.minus(3, ChronoUnit.HOURS), "640");
        ArticleMedia newest = seedItem(articleId, author.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED, now.minus(1, ChronoUnit.HOURS), "640");
        ArticleMedia middle = seedItem(articleId, author.userId(), MediaKind.IMAGE, ProcessingStatus.READY,
                PublicationStatus.PUBLISHED, now.minus(2, ChronoUnit.HOURS), "640");
        // Neither of these is public, so neither is listed.
        seedItem(articleId, author.userId(), MediaKind.IMAGE, ProcessingStatus.READY, PublicationStatus.HIDDEN, now, "640");
        seedItem(articleId, author.userId(), MediaKind.IMAGE, ProcessingStatus.READY, PublicationStatus.PENDING, now, "640");

        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data[1].id").value(middle.getId().toString()));
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(oldest.getId().toString()));
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("language", "x".repeat(21)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/articles/" + articleId + "/media").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + newest.getId()).param("language", "x".repeat(21)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/articles/" + UUID.randomUUID() + "/media"))
                .andExpect(status().isNotFound());

        // A draft article's gallery is a 404 to strangers, like the article itself, and readable
        // by its author.
        UUID draftId = createDraftArticle(author);
        mockMvc.perform(get("/api/articles/" + draftId + "/media"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + draftId + "/media").cookie(author.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // --- Regression: the task detail route for the pre-existing kind of task, and its guard ---

    @Test
    void revisionTaskDetailCarriesNoMediaReviewAndTheRouteIsModeratorOnly() throws Exception {
        Account author = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        UUID articleId = createDraftArticle(author);
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(author.accessCookie()))
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        mockMvc.perform(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit")
                        .cookie(author.accessCookie())
                        .with(xsrf()))
                .andExpect(status().isOk());
        UUID taskId = moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId();

        mockMvc.perform(get("/api/moderation/tasks/" + taskId).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.revisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.task.mediaMetadataVersionId").value(nullValue()))
                .andExpect(jsonPath("$.data.mediaReview").value(nullValue()));
        // The queue list is unchanged by the detail route: still the plain task shape.
        mockMvc.perform(get("/api/moderation/tasks").param("state", "OPEN").param("size", "100")
                        .cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + taskId + "')].revisionId").value(revisionId.toString()));

        mockMvc.perform(get("/api/moderation/tasks/" + taskId).cookie(author.accessCookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/moderation/tasks/" + taskId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/moderation/tasks/" + UUID.randomUUID()).cookie(moderator.accessCookie()))
                .andExpect(status().isNotFound());
    }
}

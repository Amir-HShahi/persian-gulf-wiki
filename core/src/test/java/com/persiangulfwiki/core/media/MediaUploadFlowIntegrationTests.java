package com.persiangulfwiki.core.media;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.HeadingReference;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaDescriptionRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Gallery upload flow against a real Postgres, Redis and MinIO: reserve -> the "browser" PUTs
// straight to the presigned URL -> complete. The storage half is the part a mock would hide,
// so the PUT goes over real HTTP to the MinIO container, and the tests assert what the store
// itself enforces (the signed checksum) as well as what the API does.
//
// Structured like the other flow tests: helpers, then the new behavior (smoke), then the
// limits and refusals, then regression coverage for the adjacent article routes this change
// shares a URL prefix and a public-read matcher with.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MediaUploadFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Random RANDOM = new Random();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Autowired
    private MediaDescriptionRepository mediaDescriptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private MediaCleanupJob mediaCleanupJob;

    @Value("${app.storage.staging-bucket}")
    private String stagingBucket;

    @Value("${app.media.job-stream}")
    private String jobStream;

    @Value("${app.media.reservations-per-window}")
    private long reservationsPerWindow;

    @Value("${app.media.reservation-window-minutes}")
    private long reservationWindowMinutes;

    @Value("${app.media.max-pending-per-user}")
    private long maxPendingPerUser;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    private record Reservation(UUID mediaId, String uploadUrl, Map<String, String> requiredHeaders) {
    }

    private Account verifiedAccount() throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "media-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("media_" + slug, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        Cookie access = mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        return new Account(user.getId(), access);
    }

    // A draft article: readable only by its author (and moderators), which is exactly the
    // visibility the reserve route has to respect.
    private UUID createArticle(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                "media-" + UUID.randomUUID(), "Title", objectMapper.readTree("{\"text\":\"body\"}"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] randomFile(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private ResultActions reserveRaw(Account caller, UUID articleId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/articles/" + articleId + "/media")
                .cookie(caller.accessCookie())
                .with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> reserveBody(String type, String contentType, long bytes, String sha256,
            Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("contentType", contentType);
        body.put("bytes", bytes);
        body.put("sha256", sha256);
        if (metadata != null) {
            body.put("metadata", metadata);
        }
        return body;
    }

    private Reservation reserve(Account caller, UUID articleId, byte[] file) throws Exception {
        String response = reserveRaw(caller, articleId, reserveBody("IMAGE", "image/jpeg", file.length, sha256(file), null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> headers = JsonPath.read(response, "$.data.requiredHeaders");
        return new Reservation(UUID.fromString(JsonPath.read(response, "$.data.mediaId")),
                JsonPath.read(response, "$.data.uploadUrl"), headers);
    }

    // What the browser does: a plain PUT to the presigned URL with the required headers.
    private static HttpResponse<String> putToStorage(Reservation reservation, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(reservation.uploadUrl()))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        reservation.requiredHeaders().forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ResultActions complete(Account caller, UUID articleId, UUID mediaId) throws Exception {
        return mockMvc.perform(post("/api/articles/" + articleId + "/media/" + mediaId + "/complete")
                .cookie(caller.accessCookie())
                .with(xsrf()));
    }

    private boolean stagingObjectExists(UUID mediaId) {
        try {
            s3Client.headObject(builder -> builder.bucket(stagingBucket).key(mediaId.toString()));
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    private List<MapRecord<String, Object, Object>> jobsFor(UUID mediaId) {
        List<MapRecord<String, Object, Object>> all = redisTemplate.opsForStream().read(StreamOffset.fromStart(jobStream));
        return all == null ? List.of() : all.stream()
                .filter(record -> mediaId.toString().equals(record.getValue().get("submission_id")))
                .toList();
    }

    // Seeds an item straight into the table for the limit tests, which need an account that is
    // already at a cap -- reaching it through the API would take twenty real reservations.
    private ArticleMedia seedItem(UUID articleId, UUID uploaderId, long bytes, ProcessingStatus processing,
            PublicationStatus publication) throws Exception {
        return articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(MediaKind.IMAGE)
                .uploadedBy(uploaderId)
                .declaredContentType("image/jpeg")
                .declaredBytes(bytes)
                .declaredSha256(sha256(randomFile(16)))
                .processingStatus(processing)
                .publicationStatus(publication)
                .variants(new ArrayList<>())
                .build());
    }

    // --- Smoke: the headline flow ---

    @Test
    void reservePutAndCompleteVerifiesUploadAndQueuesProcessingJob() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        byte[] file = randomFile(4096);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("shotAt", "2026-04-12T06:30:00Z");
        metadata.put("shotAtOffset", "+03:30");
        metadata.put("location", Map.of("latitude", 27.05, "longitude", 56.45));
        metadata.put("altitudeM", 12.5);
        metadata.put("headingDeg", 135);
        metadata.put("headingRef", "TRUE");
        metadata.put("descriptions", Map.of("fa", "تنگه هرمز", "en", "The Strait of Hormuz"));

        String response = reserveRaw(uploader, articleId,
                        reserveBody("IMAGE", "IMAGE/JPEG", file.length, sha256(file), metadata))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mediaId").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.requiredHeaders", hasKey("content-type")))
                .andExpect(jsonPath("$.data.requiredHeaders", hasKey("x-amz-checksum-sha256")))
                .andReturn().getResponse().getContentAsString();
        Map<String, String> requiredHeaders = JsonPath.read(response, "$.data.requiredHeaders");
        // Names come back lower-cased (HTTP header names are case-insensitive). The content type
        // is normalized before signing, so the value the client must send back is lower-cased too.
        assertThat(requiredHeaders).containsEntry("content-type", "image/jpeg")
                .doesNotContainKeys("host", "Host", "content-length", "Content-Length");
        Reservation reservation = new Reservation(UUID.fromString(JsonPath.read(response, "$.data.mediaId")),
                JsonPath.read(response, "$.data.uploadUrl"), requiredHeaders);

        // Reserved state: item UPLOADING/PENDING, first metadata version PENDING_REVIEW with every
        // field persisted -- location round-trips through the geography column.
        ArticleMedia reserved = articleMediaRepository.findById(reservation.mediaId()).orElseThrow();
        assertThat(reserved.getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADING);
        assertThat(reserved.getPublicationStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(reserved.getUploadedBy()).isEqualTo(uploader.userId());
        assertThat(reserved.getCurrentMetadataVersionId()).isNull();
        MediaMetadataVersion version = mediaMetadataVersionRepository.findAll().stream()
                .filter(candidate -> candidate.getMediaId().equals(reservation.mediaId()))
                .findFirst().orElseThrow();
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getStatus()).isEqualTo(MetadataVersionStatus.PENDING_REVIEW);
        assertThat(version.getShotAt()).isEqualTo(Instant.parse("2026-04-12T06:30:00Z"));
        assertThat(version.getShotAtOffset()).isEqualTo("+03:30");
        assertThat(version.getLocation().getY()).isEqualTo(27.05);
        assertThat(version.getLocation().getX()).isEqualTo(56.45);
        assertThat(version.getHeadingDeg()).isEqualByComparingTo(new BigDecimal("135"));
        assertThat(version.getHeadingRef()).isEqualTo(HeadingReference.TRUE);
        assertThat(mediaDescriptionRepository.findByMetadataVersionId(version.getId()))
                .extracting("language").containsExactlyInAnyOrder("fa", "en");

        // The browser's PUT goes straight to storage.
        assertThat(putToStorage(reservation, file).statusCode()).isEqualTo(200);

        complete(uploader, articleId, reservation.mediaId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(reservation.mediaId().toString()))
                .andExpect(jsonPath("$.data.processingStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.publicationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.failureCode").value(nullValue()));

        // One job, carrying the contract fields the worker reads.
        List<MapRecord<String, Object, Object>> jobs = jobsFor(reservation.mediaId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().getValue())
                .containsEntry("version", "1")
                .containsEntry("type", "media.image")
                .containsEntry("object_key", reservation.mediaId().toString())
                .containsEntry("content_type", "image/jpeg")
                .containsEntry("declared_bytes", String.valueOf(file.length))
                .containsEntry("declared_sha256", sha256(file))
                .containsKey("job_id");
        // The item holds the job it now waits on; results are matched against it.
        assertThat(articleMediaRepository.findById(reservation.mediaId()).orElseThrow().getProcessingJobId())
                .hasToString((String) jobs.getFirst().getValue().get("job_id"));

        // The uploader can poll it; a second /complete is refused rather than re-queued.
        mockMvc.perform(get("/api/articles/" + articleId + "/media/" + reservation.mediaId())
                        .cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("PROCESSING"));
        complete(uploader, articleId, reservation.mediaId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_AWAITING_UPLOAD"));
        assertThat(jobsFor(reservation.mediaId())).hasSize(1);
    }

    // The store, not the API, is what refuses a different file: the checksum is signed into the
    // URL. This is the property the whole direct-to-storage design leans on, so it is asserted
    // against the real server rather than assumed from the SDK's documentation.
    @Test
    void storageRejectsAPutWhoseBytesDoNotMatchTheSignedChecksum() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        byte[] declared = randomFile(2048);
        Reservation reservation = reserve(uploader, articleId, declared);

        HttpResponse<String> swapped = putToStorage(reservation, randomFile(2048));
        assertThat(swapped.statusCode()).isEqualTo(400);
        assertThat(stagingObjectExists(reservation.mediaId())).isFalse();

        // Dropping the checksum header breaks the signature instead.
        Reservation unsignedAttempt = new Reservation(reservation.mediaId(), reservation.uploadUrl(),
                Map.of("content-type", "image/jpeg"));
        assertThat(putToStorage(unsignedAttempt, declared).statusCode()).isBetween(400, 403);
        assertThat(stagingObjectExists(reservation.mediaId())).isFalse();
    }

    @Test
    void completeWithNothingUploadedMarksItemFailedUploadMissing() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        Reservation reservation = reserve(uploader, articleId, randomFile(1024));

        complete(uploader, articleId, reservation.mediaId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("upload_missing"))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("فایلی برای این رسانه دریافت نشد. لطفاً فایل را مجدداً بارگذاری فرمایید"));
        assertThat(jobsFor(reservation.mediaId())).isEmpty();
        // Never queued, so no job for a result to be matched against.
        assertThat(articleMediaRepository.findById(reservation.mediaId()).orElseThrow().getProcessingJobId()).isNull();
    }

    // A size mismatch cannot get past the presigned PUT, so the object is planted directly -- the
    // case this guards is a store that did not enforce the signature, or an object written by
    // anything other than the presigned URL.
    @Test
    void completeWithAMismatchedStoredObjectMarksItemFailedAndDeletesTheObject() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        Reservation reservation = reserve(uploader, articleId, randomFile(1024));
        s3Client.putObject(PutObjectRequest.builder().bucket(stagingBucket).key(reservation.mediaId().toString()).build(),
                RequestBody.fromBytes(randomFile(512)));

        complete(uploader, articleId, reservation.mediaId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("upload_mismatch"));
        assertThat(stagingObjectExists(reservation.mediaId())).isFalse();
        assertThat(jobsFor(reservation.mediaId())).isEmpty();
    }

    @Test
    void onlyTheUploaderMayCompleteAndOnlyTheUploaderSeesAPendingItem() throws Exception {
        Account uploader = verifiedAccount();
        Account stranger = verifiedAccount();
        UUID articleId = createArticle(uploader);
        Reservation reservation = reserve(uploader, articleId, randomFile(1024));
        String itemUrl = "/api/articles/" + articleId + "/media/" + reservation.mediaId();

        complete(stranger, articleId, reservation.mediaId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MEDIA_UPLOADER"));
        assertThat(articleMediaRepository.findById(reservation.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.UPLOADING);

        mockMvc.perform(get(itemUrl)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        mockMvc.perform(get(itemUrl).cookie(stranger.accessCookie())).andExpect(status().isNotFound());
        mockMvc.perform(get(itemUrl).cookie(uploader.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("IMAGE"))
                .andExpect(jsonPath("$.data.processingStatus").value("UPLOADING"));

        // Scoped to its article: the same id under another article is not found.
        mockMvc.perform(get("/api/articles/" + UUID.randomUUID() + "/media/" + reservation.mediaId())
                        .cookie(uploader.accessCookie()))
                .andExpect(status().isNotFound());
    }

    // --- Refusals at reservation ---

    @Test
    void reserveRejectsDisallowedContentTypeOversizeAndBadMetadata() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        String sha = sha256(randomFile(8));

        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/gif", 100, sha, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_CONTENT_TYPE"));
        reserveRaw(uploader, articleId, reserveBody("PANORAMA_360", "video/mp4", 100, sha, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_CONTENT_TYPE"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 26214401, sha, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEDIA_TOO_LARGE"));
        // The same size is fine for a panorama, whose limit is higher.
        reserveRaw(uploader, articleId, reserveBody("PANORAMA_360", "image/jpeg", 26214401, sha, null))
                .andExpect(status().isCreated());

        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, "not-a-hash", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sha256"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(),
                        Map.of("location", Map.of("latitude", 91, "longitude", 10))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("metadata.location.latitude"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(), Map.of("headingDeg", 360)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("metadata.headingDeg"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(),
                        Map.of("shotAt", Instant.now().plusSeconds(3600).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("metadata.shotAt"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(), Map.of("shotAtOffset", "+03:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_MEDIA_METADATA"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(), Map.of("headingRef", "TRUE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_MEDIA_METADATA"));
    }

    private static String sha() throws Exception {
        return sha256(randomFile(8));
    }

    @Test
    void reserveRequiresASignedInCallerWhoCanSeeTheArticle() throws Exception {
        Account author = verifiedAccount();
        Account stranger = verifiedAccount();
        UUID draftArticleId = createArticle(author);
        Map<String, Object> body = reserveBody("IMAGE", "image/jpeg", 100, sha(), null);

        mockMvc.perform(post("/api/articles/" + draftArticleId + "/media")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        // CSRF still guards the new write route.
        mockMvc.perform(post("/api/articles/" + draftArticleId + "/media")
                        .cookie(author.accessCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        // A draft article is invisible to a stranger, so uploading to it is a 404, not a 403.
        reserveRaw(stranger, draftArticleId, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        reserveRaw(author, UUID.randomUUID(), body)
                .andExpect(status().isNotFound());
    }

    @Test
    void reserveRefusesAFileAlreadyInTheArticleGalleryUnlessTheEarlierOneFailed() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        byte[] file = randomFile(1024);
        Reservation first = reserve(uploader, articleId, file);

        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", file.length, sha256(file), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEDIA"));
        // Another article may use the same file.
        reserveRaw(uploader, createArticle(uploader), reserveBody("IMAGE", "image/jpeg", file.length, sha256(file), null))
                .andExpect(status().isCreated());

        // Once the first attempt has failed it no longer blocks a retry of the same file.
        complete(uploader, articleId, first.mediaId()).andExpect(jsonPath("$.data.processingStatus").value("FAILED"));
        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", file.length, sha256(file), null))
                .andExpect(status().isCreated());
    }

    @Test
    void reserveIsRateLimitedWithRetryAfter() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        // Put this account at the limit for the current window (and the next, in case the
        // window rolls over mid-test) rather than making thirty real reservations.
        long windowSeconds = Duration.ofMinutes(reservationWindowMinutes).toSeconds();
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        for (long start : List.of(windowStart, windowStart + windowSeconds)) {
            redisTemplate.opsForValue().set("media:reservations:" + uploader.userId() + ":" + start,
                    String.valueOf(reservationsPerWindow), Duration.ofSeconds(2 * windowSeconds));
        }

        reserveRaw(uploader, articleId, reserveBody("IMAGE", "image/jpeg", 100, sha(), null))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("MEDIA_UPLOAD_RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void reserveRefusesAnAccountAtItsPendingCapOrStorageQuota() throws Exception {
        Account busy = verifiedAccount();
        UUID busyArticle = createArticle(busy);
        for (int i = 0; i < maxPendingPerUser; i++) {
            seedItem(busyArticle, busy.userId(), 100, ProcessingStatus.PROCESSING, PublicationStatus.PENDING);
        }
        reserveRaw(busy, busyArticle, reserveBody("IMAGE", "image/jpeg", 100, sha(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENDING_MEDIA_LIMIT_EXCEEDED"));

        Account full = verifiedAccount();
        UUID fullArticle = createArticle(full);
        // Starts FAILED, which holds no quota; flipped to READY below, which does.
        ArticleMedia big = seedItem(fullArticle, full.userId(), 2147483000L, ProcessingStatus.FAILED,
                PublicationStatus.PENDING);
        reserveRaw(full, fullArticle, reserveBody("IMAGE", "image/jpeg", 1000, sha(), null))
                .andExpect(status().isCreated());
        big.setProcessingStatus(ProcessingStatus.READY);
        articleMediaRepository.save(big);
        reserveRaw(full, fullArticle, reserveBody("IMAGE", "image/jpeg", 1000, sha(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_STORAGE_QUOTA_EXCEEDED"));
    }

    // --- Cleanup job ---

    @Test
    void cleanupJobDeletesAbandonedUploadsAndExpiredRejectionsWithTheirObjects() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        byte[] file = randomFile(1024);
        Reservation abandoned = reserve(uploader, articleId, file);
        assertThat(putToStorage(abandoned, file).statusCode()).isEqualTo(200);
        ArticleMedia rejected = seedItem(articleId, uploader.userId(), 100, ProcessingStatus.READY,
                PublicationStatus.REJECTED);
        ArticleMedia live = seedItem(articleId, uploader.userId(), 100, ProcessingStatus.PROCESSING,
                PublicationStatus.PENDING);

        // A threshold in the past sweeps nothing this test just made.
        mediaCleanupJob.sweepAbandonedUploads(Instant.now().minusSeconds(3600));
        mediaCleanupJob.sweepRejected(Instant.now().minusSeconds(3600));
        assertThat(articleMediaRepository.existsById(abandoned.mediaId())).isTrue();
        assertThat(articleMediaRepository.existsById(rejected.getId())).isTrue();

        mediaCleanupJob.sweepAbandonedUploads(Instant.now().plusSeconds(1));
        mediaCleanupJob.sweepRejected(Instant.now().plusSeconds(1));
        assertThat(articleMediaRepository.existsById(abandoned.mediaId())).isFalse();
        assertThat(stagingObjectExists(abandoned.mediaId())).isFalse();
        assertThat(articleMediaRepository.existsById(rejected.getId())).isFalse();
        // Neither sweep touches an item that is merely in flight.
        assertThat(articleMediaRepository.existsById(live.getId())).isTrue();
    }

    // --- Schema ---

    @Test
    void variantsRoundTripThroughJsonbAndPublishedItemsMustHaveMetadata() throws Exception {
        Account uploader = verifiedAccount();
        UUID articleId = createArticle(uploader);
        ArticleMedia item = seedItem(articleId, uploader.userId(), 100, ProcessingStatus.READY, PublicationStatus.PENDING);
        item.setVariants(List.of(new MediaVariant("640", "webp", "k/640.webp", 1234, 640, 320)));
        articleMediaRepository.save(item);
        assertThat(articleMediaRepository.findById(item.getId()).orElseThrow().getVariants())
                .containsExactly(new MediaVariant("640", "webp", "k/640.webp", 1234, 640, 320));

        // ck_article_media_published_has_metadata: PUBLISHED without a current version is refused
        // by the database itself, whatever path tries it.
        ArticleMedia reloaded = articleMediaRepository.findById(item.getId()).orElseThrow();
        reloaded.setPublicationStatus(PublicationStatus.PUBLISHED);
        assertThatThrownBy(() -> articleMediaRepository.saveAndFlush(reloaded))
                .hasMessageContaining("ck_article_media_published_has_metadata");
    }

    // --- Regression: adjacent article routes under the same prefix and public-read matcher ---

    @Test
    void articleRoutesAreUnchangedByTheNestedMediaRoutes() throws Exception {
        Account author = verifiedAccount();
        UUID articleId = createArticle(author);
        // A draft article is still hidden from anonymous callers and visible to its author...
        mockMvc.perform(get("/api/articles/" + articleId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + articleId).cookie(author.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(articleId.toString()));
        // ...and the public list still answers anonymously.
        mockMvc.perform(get("/api/articles").param("language", "fa")).andExpect(status().isOk());
    }
}

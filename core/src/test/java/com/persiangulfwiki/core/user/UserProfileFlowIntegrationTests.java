package com.persiangulfwiki.core.user;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;
import com.persiangulfwiki.core.user.service.AvatarService;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The public profile against a real Postgres, Redis and MinIO: editing the profile, the avatar
// upload (reserve -> PUT straight to storage -> complete), the public read and the contributed
// articles. Then regression coverage for what this change touched: GET /me's existing fields,
// registration (which must stay as it was), and the /api/users routes that must stay private
// now that /api/profiles is public.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PLACEHOLDER = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private AvatarCleanupJob avatarCleanupJob;

    @Autowired
    private AvatarService avatarService;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Value("${app.storage.staging-bucket}")
    private String stagingBucket;

    @Value("${app.storage.media-bucket}")
    private String mediaBucket;

    @Value("${app.storage.public-base-url}")
    private String publicBaseUrl;

    // --- Helpers ---

    private record Account(UUID userId, String username, Cookie accessCookie) {
    }

    private record Reservation(UUID avatarId, String uploadUrl, Map<String, String> requiredHeaders) {
    }

    private Account verifiedAccount() throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String username = "prof_" + slug;
        String email = "profile-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
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
        return new Account(user.getId(), username, access);
    }

    private ResultActions updateProfile(Account caller, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/users/me/profile")
                .cookie(caller.accessCookie())
                .with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> profileBody(String fullName, String bio, String affiliation, List<String> links) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("bio", bio);
        body.put("affiliation", affiliation);
        body.put("links", links);
        return body;
    }

    private ResultActions reserveRaw(Account caller, String contentType, long bytes, String sha256) throws Exception {
        return mockMvc.perform(post("/api/users/me/avatar")
                .cookie(caller.accessCookie())
                .with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("contentType", contentType, "bytes", bytes,
                        "sha256", sha256))));
    }

    private Reservation reserve(Account caller, String contentType, byte[] file) throws Exception {
        String response = reserveRaw(caller, contentType, file.length, sha256(file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> headers = JsonPath.read(response, "$.data.requiredHeaders");
        return new Reservation(UUID.fromString(JsonPath.read(response, "$.data.avatarId")),
                JsonPath.read(response, "$.data.uploadUrl"), headers);
    }

    private static void putToStorage(Reservation reservation, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(reservation.uploadUrl()))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        reservation.requiredHeaders().forEach(request::header);
        assertThat(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private ResultActions complete(Account caller, UUID avatarId) throws Exception {
        return mockMvc.perform(post("/api/users/me/avatar/" + avatarId + "/complete")
                .cookie(caller.accessCookie())
                .with(xsrf()));
    }

    private String uploadAvatar(Account caller, byte[] image, String contentType) throws Exception {
        Reservation reservation = reserve(caller, contentType, image);
        putToStorage(reservation, image);
        String response = complete(caller, reservation.avatarId())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.avatarUrl");
    }

    private Account completeProfile() throws Exception {
        Account account = verifiedAccount();
        updateProfile(account, profileBody("Test Person", null, null, null)).andExpect(status().isOk());
        uploadAvatar(account, image(200, 100, "png"), "image/png");
        return account;
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    // A JPEG carrying an EXIF-style APP1 segment right after SOI, as a phone camera writes one.
    private static byte[] jpegWithExif(byte[] jpeg, String marker) {
        byte[] payload = ("Exif\0\0" + marker).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        byte[] out = new byte[jpeg.length + 4 + payload.length];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        out[2] = (byte) 0xFF;
        out[3] = (byte) 0xE1;
        out[4] = (byte) (length >> 8);
        out[5] = (byte) length;
        System.arraycopy(payload, 0, out, 6, payload.length);
        System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
        return out;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String mediaKeyOf(String avatarUrl) {
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        assertThat(avatarUrl).startsWith(base);
        return "public/" + avatarUrl.substring(base.length());
    }

    private byte[] readMediaObject(String key) {
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(builder -> builder.bucket(mediaBucket).key(key));
        return object.asByteArray();
    }

    private boolean objectExists(String bucket, String key) {
        try {
            s3Client.headObject(builder -> builder.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    // An article created through the API, with its first revision approved the way a moderator's
    // decision applies it -- so it is public, created by and revised by `author`.
    private UUID publicArticle(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, "fa", "prof-" + UUID.randomUUID(), "Title",
                objectMapper.readTree("{\"text\":\"body\"}"), "Summary.", null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        articleRevisionService.applyModerationOutcome(firstRevisionId(articleId, "fa", author), RevisionStatus.APPROVED);
        return articleId;
    }

    private void approvedTranslation(Account translator, UUID articleId, String language) throws Exception {
        CreateTranslationRequest request = new CreateTranslationRequest(language, "prof-" + UUID.randomUUID(),
                "Title " + language, objectMapper.readTree("{\"text\":\"body\"}"), null);
        mockMvc.perform(post("/api/articles/" + articleId + "/translations")
                        .cookie(translator.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        articleRevisionService.applyModerationOutcome(firstRevisionId(articleId, language, translator),
                RevisionStatus.APPROVED);
    }

    private UUID firstRevisionId(UUID articleId, String language, Account reader) throws Exception {
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/" + language + "/revisions")
                        .cookie(reader.accessCookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
    }

    // Seeded: publishing media takes the pipeline worker and a moderator, neither of which the
    // contribution read path depends on -- it only reads the published row.
    private void publishedMedia(Account uploader, UUID articleId) {
        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(MediaKind.IMAGE)
                .uploadedBy(uploader.userId())
                .declaredContentType("image/jpeg")
                .declaredBytes(1024)
                .declaredSha256(Base64.getEncoder().encodeToString(new byte[32]))
                .processingStatus(ProcessingStatus.READY)
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .build());
        MediaMetadataVersion version = mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                .mediaId(media.getId())
                .versionNumber(1)
                .submittedBy(uploader.userId())
                .status(MetadataVersionStatus.APPROVED)
                .build());
        media.setPlaceholder(PLACEHOLDER);
        media.setCurrentMetadataVersionId(version.getId());
        media.setPublishedAt(Instant.now());
        media.setPublicationStatus(PublicationStatus.PUBLISHED);
        articleMediaRepository.save(media);
    }

    // --- Smoke: editing the profile ---

    @Test
    void newAccountStartsWithAnIncompleteEmptyProfile() throws Exception {
        Account account = verifiedAccount();

        mockMvc.perform(get("/api/users/me").cookie(account.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value(nullValue()))
                .andExpect(jsonPath("$.data.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.links", hasSize(0)))
                .andExpect(jsonPath("$.data.joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.isProfileComplete").value(false));
    }

    @Test
    void updateProfileStoresAndReturnsEveryField() throws Exception {
        Account account = verifiedAccount();

        updateProfile(account, profileBody("  Amir Hasanshahi ", "Marine biologist.", "University of Tehran",
                List.of("https://orcid.org/0000-0002-1825-0097", "https://example.com/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Amir Hasanshahi"))
                .andExpect(jsonPath("$.data.bio").value("Marine biologist."))
                .andExpect(jsonPath("$.data.affiliation").value("University of Tehran"))
                .andExpect(jsonPath("$.data.links[0]").value("https://orcid.org/0000-0002-1825-0097"))
                .andExpect(jsonPath("$.data.links[1]").value("https://example.com/me"))
                .andExpect(jsonPath("$.data.isProfileComplete").value(false));

        // A full replacement: what is left out is cleared, and the links keep the new order.
        updateProfile(account, profileBody("Amir", "", null, List.of("https://example.com/b", "https://example.com/a")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").cookie(account.accessCookie()))
                .andExpect(jsonPath("$.data.fullName").value("Amir"))
                .andExpect(jsonPath("$.data.bio").value(nullValue()))
                .andExpect(jsonPath("$.data.affiliation").value(nullValue()))
                .andExpect(jsonPath("$.data.links[0]").value("https://example.com/b"))
                .andExpect(jsonPath("$.data.links[1]").value("https://example.com/a"));
    }

    @Test
    void invalidProfileFieldsAreRejected() throws Exception {
        Account account = verifiedAccount();

        updateProfile(account, profileBody(" ", null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fullName"));
        updateProfile(account, profileBody("x".repeat(101), null, null, null)).andExpect(status().isBadRequest());
        updateProfile(account, profileBody("Name", "x".repeat(501), null, null)).andExpect(status().isBadRequest());
        updateProfile(account, profileBody("Name", null, "x".repeat(151), null)).andExpect(status().isBadRequest());
        updateProfile(account, profileBody("Name", null, null,
                List.of("https://a.com", "https://b.com", "https://c.com", "https://d.com", "https://e.com",
                        "https://f.com"))).andExpect(status().isBadRequest());
        for (String link : List.of("javascript:alert(1)", "http://example.com", "https://", "data:text/html,x",
                "https://exa mple.com")) {
            updateProfile(account, profileBody("Name", null, null, List.of(link))).andExpect(status().isBadRequest());
        }

        assertThat(userRepository.findById(account.userId()).orElseThrow().getFullName()).isNull();
    }

    @Test
    void updateProfileWithoutCsrfIsRejected() throws Exception {
        Account account = verifiedAccount();
        mockMvc.perform(put("/api/users/me/profile")
                        .cookie(account.accessCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody("Name", null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateProfileWithoutLoginIsRejected() throws Exception {
        mockMvc.perform(put("/api/users/me/profile")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody("Name", null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    // --- Smoke: the avatar upload ---

    @Test
    void avatarUploadPublishesASquareJpegWithoutTheUploadsMetadata() throws Exception {
        Account account = verifiedAccount();
        byte[] upload = jpegWithExif(image(800, 600, "jpeg"), "GPS-SECRET-MARKER");

        String avatarUrl = uploadAvatar(account, upload, "image/jpeg");

        byte[] published = readMediaObject(mediaKeyOf(avatarUrl));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(published));
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(512);
        assertThat(new String(published, StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET-MARKER");

        User user = userRepository.findById(account.userId()).orElseThrow();
        assertThat(user.getAvatarUploadId()).isNull();
        mockMvc.perform(get("/api/users/me").cookie(account.accessCookie()))
                .andExpect(jsonPath("$.data.avatarUrl").value(avatarUrl));
    }

    @Test
    void newAvatarReplacesAndDeletesTheOldOne() throws Exception {
        Account account = verifiedAccount();
        String first = uploadAvatar(account, image(100, 100, "png"), "image/png");
        String second = uploadAvatar(account, image(300, 300, "jpeg"), "image/jpeg");

        assertThat(second).isNotEqualTo(first);
        assertThat(objectExists(mediaBucket, mediaKeyOf(first))).isFalse();
        assertThat(objectExists(mediaBucket, mediaKeyOf(second))).isTrue();
    }

    @Test
    void avatarReservationRefusesBadDeclarations() throws Exception {
        Account account = verifiedAccount();
        String hash = sha256(new byte[] {1});

        reserveRaw(account, "image/webp", 1000, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_AVATAR_CONTENT_TYPE"));
        reserveRaw(account, "image/jpeg", 5 * 1024 * 1024 + 1, hash)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AVATAR_TOO_LARGE"));
        reserveRaw(account, "image/jpeg", 1000, "not-a-hash").andExpect(status().isBadRequest());
    }

    @Test
    void completeBeforeUploadIsAConflictAndAStrangersIdIsNotFound() throws Exception {
        Account account = verifiedAccount();
        Account other = verifiedAccount();
        Reservation reservation = reserve(account, "image/png", image(100, 100, "png"));

        complete(account, reservation.avatarId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AVATAR_UPLOAD_MISSING"));
        complete(other, reservation.avatarId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AVATAR_NOT_FOUND"));
        complete(account, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void uploadThatIsNotAnImageOrTooSmallIsRejected() throws Exception {
        Account account = verifiedAccount();

        byte[] notAnImage = "definitely not a png".getBytes(StandardCharsets.UTF_8);
        Reservation fake = reserve(account, "image/png", notAnImage);
        putToStorage(fake, notAnImage);
        complete(account, fake.avatarId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AVATAR_IMAGE"));

        // A JPEG declared as PNG is refused too: the declared type must be what actually decodes.
        byte[] jpeg = image(100, 100, "jpeg");
        Reservation mislabeled = reserve(account, "image/png", jpeg);
        putToStorage(mislabeled, jpeg);
        complete(account, mislabeled.avatarId()).andExpect(status().isBadRequest());

        byte[] tiny = image(10, 10, "png");
        Reservation small = reserve(account, "image/png", tiny);
        putToStorage(small, tiny);
        complete(account, small.avatarId()).andExpect(status().isBadRequest());

        assertThat(userRepository.findById(account.userId()).orElseThrow().getAvatarKey()).isNull();
    }

    @Test
    void newReservationReplacesTheOutstandingOne() throws Exception {
        Account account = verifiedAccount();
        byte[] file = image(100, 100, "png");
        Reservation first = reserve(account, "image/png", file);
        putToStorage(first, file);
        String firstKey = "avatars/" + account.userId() + "/" + first.avatarId();

        Reservation second = reserve(account, "image/png", file);

        complete(account, first.avatarId()).andExpect(status().isNotFound());
        assertThat(objectExists(stagingBucket, firstKey)).isFalse();
        putToStorage(second, file);
        complete(account, second.avatarId()).andExpect(status().isOk());
    }

    @Test
    void cleanupSweepsAbandonedReservationsAndTheirUploads() throws Exception {
        Account account = verifiedAccount();
        byte[] file = image(100, 100, "png");
        Reservation reservation = reserve(account, "image/png", file);
        putToStorage(reservation, file);
        String stagingKey = "avatars/" + account.userId() + "/" + reservation.avatarId();

        // The scheduled run only sweeps reservations expired over an hour ago, so this one stays.
        avatarCleanupJob.sweep();
        assertThat(userRepository.findById(account.userId()).orElseThrow().getAvatarUploadId())
                .isEqualTo(reservation.avatarId());

        assertThat(avatarService.sweepAbandonedUploads(Instant.now().plus(Duration.ofDays(1)))).isPositive();

        assertThat(userRepository.findById(account.userId()).orElseThrow().getAvatarUploadId()).isNull();
        assertThat(objectExists(stagingBucket, stagingKey)).isFalse();
        complete(account, reservation.avatarId()).andExpect(status().isNotFound());
    }

    // --- Smoke: the public profile ---

    @Test
    void profileIsPublicOnlyOnceNameAndAvatarAreBothSet() throws Exception {
        Account account = verifiedAccount();
        mockMvc.perform(get("/api/profiles/" + account.username()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        updateProfile(account, profileBody("Public Person", "Bio.", "Institute", List.of("https://example.com")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/profiles/" + account.username())).andExpect(status().isNotFound());

        String avatarUrl = uploadAvatar(account, image(100, 100, "png"), "image/png");

        mockMvc.perform(get("/api/profiles/" + account.username().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(account.username()))
                .andExpect(jsonPath("$.data.fullName").value("Public Person"))
                .andExpect(jsonPath("$.data.avatarUrl").value(avatarUrl))
                .andExpect(jsonPath("$.data.bio").value("Bio."))
                .andExpect(jsonPath("$.data.affiliation").value("Institute"))
                .andExpect(jsonPath("$.data.links[0]").value("https://example.com"))
                .andExpect(jsonPath("$.data.joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[0]").value("CONTRIBUTOR"))
                .andExpect(jsonPath("$.data.contributionCount").value(0))
                .andExpect(jsonPath("$.data.email").doesNotExist());
        mockMvc.perform(get("/api/users/me").cookie(account.accessCookie()))
                .andExpect(jsonPath("$.data.isProfileComplete").value(true));
    }

    @Test
    void disabledAccountsProfileIsNotFound() throws Exception {
        Account account = completeProfile();
        User user = userRepository.findById(account.userId()).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        mockMvc.perform(get("/api/profiles/" + account.username())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/profiles/" + account.username() + "/contributed-articles"))
                .andExpect(status().isNotFound());
    }

    @Test
    void contributedArticlesCountEveryKindOncePerArticle() throws Exception {
        Account author = completeProfile();
        Account translator = completeProfile();

        // author: CREATED + REVISION on the first; translator adds a translation and media to it.
        UUID first = publicArticle(author);
        approvedTranslation(translator, first, "en");
        publishedMedia(translator, first);
        // author: a second article, contributed to later.
        UUID second = publicArticle(author);
        // A draft (nothing approved) is nobody's public contribution.
        CreateArticleRequest draft = new CreateArticleRequest(null, "fa", "prof-" + UUID.randomUUID(), "Draft",
                objectMapper.readTree("{\"text\":\"body\"}"), null, null);
        mockMvc.perform(post("/api/articles").cookie(author.accessCookie()).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(draft)))
                .andExpect(status().isCreated());

        // 2 created + 2 approved revisions.
        mockMvc.perform(get("/api/profiles/" + author.username()))
                .andExpect(jsonPath("$.data.contributionCount").value(4));
        mockMvc.perform(get("/api/profiles/" + author.username() + "/contributed-articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].article.id").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].contributionTypes", containsInAnyOrder("CREATED", "REVISION")))
                .andExpect(jsonPath("$.data.items[1].article.id").value(first.toString()));

        // 1 approved revision (its translation's first) + 1 translation + 1 media item.
        mockMvc.perform(get("/api/profiles/" + translator.username()))
                .andExpect(jsonPath("$.data.contributionCount").value(3));
        mockMvc.perform(get("/api/profiles/" + translator.username() + "/contributed-articles")
                        .header("Accept-Language", "en"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].article.id").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].article.language").value("en"))
                .andExpect(jsonPath("$.data.items[0].article.title").value("Title en"))
                .andExpect(jsonPath("$.data.items[0].contributionTypes",
                        containsInAnyOrder("REVISION", "TRANSLATION", "MEDIA")));

        mockMvc.perform(get("/api/profiles/" + author.username() + "/contributed-articles?page=1&size=1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].article.id").value(first.toString()));

        // The admin user list counts from the same definition, batched over the page.
        Cookie admin = adminAccessCookie();
        mockMvc.perform(get("/api/admin/users").param("size", "10000").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + author.userId() + "')].contributionCount",
                        contains(4)))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + translator.userId() + "')].contributionCount",
                        contains(3)));
    }

    private Cookie adminAccessCookie() throws Exception {
        Account account = verifiedAccount();
        User user = userRepository.findById(account.userId()).orElseThrow();
        userRoleRepository.save(UserRole.builder().user(user).role(Role.ADMIN).build());
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    @Test
    void contributedArticlesRejectsBadPaging() throws Exception {
        Account account = completeProfile();
        mockMvc.perform(get("/api/profiles/" + account.username() + "/contributed-articles?size=101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/profiles/" + account.username() + "/contributed-articles?page=-1"))
                .andExpect(status().isBadRequest());
    }

    // --- Regression: adjacent behavior this change touched ---

    // GET /me now reads through the profile service; its original fields must be unchanged.
    @Test
    void meStillReturnsTheAccountFields() throws Exception {
        Account account = verifiedAccount();
        mockMvc.perform(get("/api/users/me").cookie(account.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(account.userId().toString()))
                .andExpect(jsonPath("$.data.username").value(account.username()))
                .andExpect(jsonPath("$.data.email").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[0]").value("CONTRIBUTOR"));
    }

    // Registration did not gain any profile field: the original three-field body still works,
    // and leaves the profile empty.
    @Test
    void registrationIsUnchangedAndLeavesTheProfileEmpty() throws Exception {
        Account account = verifiedAccount();
        User user = userRepository.findById(account.userId()).orElseThrow();
        assertThat(user.getFullName()).isNull();
        assertThat(user.getAvatarKey()).isNull();
    }

    // /api/profiles/** is public; the private /api/users routes next to it must not be.
    @Test
    void privateUserRoutesStillNeedALogin() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/avatar").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // Public reads are GET-only: no other method on /api/profiles is anonymously reachable.
    @Test
    void profilesAreNotAnonymouslyWritable() throws Exception {
        mockMvc.perform(post("/api/profiles/anyone").with(xsrf())).andExpect(status().isUnauthorized());
    }
}

package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 5: the read side -- a subject's infobox and the facet listing, both built from the read
// model that only approval writes. Subjects and articles are created through the real proposal,
// article and moderation routes; only gallery items are seeded as rows, since publishing one
// takes the media pipeline.
//
// The database is shared with every other integration test class, so the listing tests pin their
// counts down by filtering on a province each test creates for itself.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectReadSideFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SubjectRevisionRepository subjectRevisionRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeGroupRepository attributeGroupRepository;

    @Autowired
    private LabelRepository labelRepository;

    // --- Helpers ---

    private record Session(UUID userId, Cookie access, Cookie csrf) {
    }

    private record PublishedArticle(UUID id, String slug) {
    }

    private Session signIn(String name, Role... roles) throws Exception {
        return signIn(name, null, roles);
    }

    private Session expert(String name, SubjectKind expertise) throws Exception {
        return signIn(name, expertise, Role.EXPERT_REVIEWER);
    }

    private Session signIn(String name, @Nullable SubjectKind expertise, Role... roles) throws Exception {
        String email = name + "@rsf.example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(name, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        for (Role role : roles) {
            userRoleRepository.save(UserRole.builder().user(user).role(role)
                    .expertise(role == Role.EXPERT_REVIEWER ? expertise : null).build());
        }
        Cookie access = mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        Cookie csrf = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        return new Session(user.getId(), access, csrf);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Session session, @Nullable String json)
            throws Exception {
        request.cookie(session.access(), session.csrf())
                .header("X-XSRF-TOKEN", maskCsrfToken(session.csrf().getValue()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    // Anonymous, as a reader would be.
    private ResultActions infobox(UUID subjectId, String language) throws Exception {
        return mockMvc.perform(get("/api/subjects/" + subjectId + "/infobox")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language));
    }

    // Name/value pairs.
    private ResultActions listing(String language, String... parameters) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/subjects").header(HttpHeaders.ACCEPT_LANGUAGE, language);
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private String listingBody(String... parameters) throws Exception {
        return listing("en", parameters).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private UUID propose(Session author, String kind, String payload) throws Exception {
        String body = send(post("/api/subject-revisions"), author, """
                {"kind": "%s", "payload": %s}""".formatted(kind, payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private UUID startEdit(Session author, UUID subjectId, String payload) throws Exception {
        String body = send(post("/api/subjects/" + subjectId + "/revisions"), author, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        send(patch("/api/subject-revisions/" + revisionId), author, """
                {"payload": %s}""".formatted(payload))
                .andExpect(status().isOk());
        return revisionId;
    }

    private void submit(Session author, UUID revisionId) throws Exception {
        send(post("/api/subject-revisions/" + revisionId + "/submit"), author, null).andExpect(status().isOk());
    }

    private void approve(Session moderator, UUID taskId) throws Exception {
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null)))
                .andExpect(status().isOk());
    }

    // Submits and approves the revision, which makes it live.
    private void publish(Session author, Session moderator, UUID revisionId) throws Exception {
        submit(author, revisionId);
        approve(moderator, moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow().getId());
    }

    private UUID approvedSubject(Session author, Session moderator, String kind, String payload) throws Exception {
        UUID revisionId = propose(author, kind, payload);
        publish(author, moderator, revisionId);
        return subjectRevisionRepository.findById(revisionId).orElseThrow().getSubjectId();
    }

    private UUID province(Session author, Session moderator, String fa, String en) throws Exception {
        return approvedSubject(author, moderator, "PROVINCE", payload(fa, en));
    }

    // A Farsi article bound to the subject, approved through moderation.
    private PublishedArticle publishedArticle(Session author, Session moderator, UUID subjectId) throws Exception {
        String slug = "rsf-" + UUID.randomUUID();
        String created = send(post("/api/articles"), author, """
                {"subjectId": "%s", "canonicalLanguage": "fa", "slug": "%s", "title": "عنوان",
                 "body": {"text": "متن"}}""".formatted(subjectId, slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(author.access()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        send(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit"), author, null)
                .andExpect(status().isOk());
        approve(moderator, moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId());
        return new PublishedArticle(articleId, slug);
    }

    // A gallery item as the pipeline and a moderator would leave it; a published one has an
    // approved first metadata version, as the schema requires.
    private UUID seedMedia(UUID articleId, UUID uploaderId, MediaKind type, PublicationStatus publication)
            throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(type)
                .uploadedBy(uploaderId)
                .declaredContentType("image/jpeg")
                .declaredBytes(1024)
                .declaredSha256(Base64.getEncoder().encodeToString(digest))
                .processingStatus(ProcessingStatus.READY)
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .build());
        if (publication == PublicationStatus.PUBLISHED) {
            MediaMetadataVersion version = mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                    .mediaId(media.getId())
                    .versionNumber(1)
                    .submittedBy(uploaderId)
                    .status(MetadataVersionStatus.APPROVED)
                    .build());
            media.setCurrentMetadataVersionId(version.getId());
            media.setPublishedAt(Instant.now());
            // Where approval leaves the renditions (see MediaVariantService.publish).
            String prefix = MediaVariantService.publicVariantPrefix(media.getId());
            media.setVariants(new ArrayList<>(List.of(
                    new MediaVariant("640", "webp", prefix + "640.webp", 48211, 640, 480),
                    new MediaVariant("1280", "webp", prefix + "1280.webp", 151022, 1280, 960))));
            media.setPlaceholder("data:image/webp;base64,UklGR");
        }
        media.setPublicationStatus(publication);
        return articleMediaRepository.save(media).getId();
    }

    private UUID seedSource() {
        return sourceRepository.save(Source.builder().title("Census " + UUID.randomUUID()).build()).getId();
    }

    private static String payload(String fa, @Nullable String en, String... facts) {
        String names = en == null ? "{\"fa\": \"%s\"}".formatted(fa) : "{\"fa\": \"%s\", \"en\": \"%s\"}".formatted(fa, en);
        return "{\"names\": " + names + ", \"facts\": [" + String.join(", ", facts) + "]}";
    }

    private static String options(String attribute, String... codes) {
        return "{\"attribute\": \"%s\", \"values\": [%s]}".formatted(attribute, Arrays.stream(codes)
                .map(code -> "{\"option\": \"%s\"}".formatted(code))
                .collect(Collectors.joining(", ")));
    }

    private static String reference(String attribute, UUID subjectId) {
        return "{\"attribute\": \"%s\", \"values\": [{\"subjectId\": \"%s\"}]}".formatted(attribute, subjectId);
    }

    private static String number(String attribute, long value, UUID sourceId) {
        return "{\"attribute\": \"%s\", \"values\": [{\"number\": %d, \"sourceId\": \"%s\"}]}"
                .formatted(attribute, value, sourceId);
    }

    private static String text(String attribute, String value) {
        return "{\"attribute\": \"%s\", \"values\": [{\"text\": \"%s\"}]}".formatted(attribute, value);
    }

    private static List<String> itemIds(String body) {
        return JsonPath.read(body, "$.data.items[*].id");
    }

    private static Object facetCount(String body, String facet, String code) {
        List<Object> counts = JsonPath.read(body,
                "$.data.facets[?(@.key == '%s')].options[?(@.code == '%s')].count".formatted(facet, code));
        assertThat(counts).as("%s option %s", facet, code).hasSize(1);
        return counts.getFirst();
    }

    // --- Smoke: the infobox ---

    // Groups and fields in vocabulary order, only infobox fields' PREFERRED values, labels in the
    // reader's language, the derived density row, and a referenced subject with its name and the
    // Farsi slug of its main article standing in for a missing English one.
    @Test
    void infoboxShowsPreferredFactsInVocabularyOrderWithDerivedDensityAndLinkedSubjects() throws Exception {
        Session author = signIn("rsfauth1");
        Session moderator = signIn("rsfmod1", Role.MODERATOR);
        UUID sourceId = seedSource();
        UUID provinceId = province(author, moderator, "هرمزگان", "Hormozgan");
        PublishedArticle provinceArticle = publishedArticle(author, moderator, provinceId);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ قشم", "Qeshm Island",
                """
                {"attribute": "population", "values": [
                  {"number": 148993, "asOf": "2016-01-01", "sourceId": "%1$s"},
                  {"number": 117774, "asOf": "2011-01-01", "sourceId": "%1$s", "rank": "NORMAL"}]}"""
                        .formatted(sourceId),
                number("area", 1491, sourceId),
                options("country", "IR"),
                reference("province", provinceId),
                options("place_features", "FREE_ZONE", "UNESCO_GEOPARK")));

        infobox(islandId, "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectId").value(islandId.toString()))
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.name.value").value("Qeshm Island"))
                .andExpect(jsonPath("$.data.name.isFallback").value(false))
                .andExpect(jsonPath("$.data.mainImage").doesNotExist())
                .andExpect(jsonPath("$.data.groups[*].key")
                        .value(contains("geography", "administrative_divisions", "demography")))
                .andExpect(jsonPath("$.data.groups[0].label.value").value("Geography"))
                .andExpect(jsonPath("$.data.groups[0].rows[*].attribute").value(contains("area", "place_features")))
                .andExpect(jsonPath("$.data.groups[0].rows[0].unit").value("km2"))
                .andExpect(jsonPath("$.data.groups[0].rows[0].values[0].number").value(1491))
                .andExpect(jsonPath("$.data.groups[0].rows[1].values[*].option.code")
                        .value(contains("FREE_ZONE", "UNESCO_GEOPARK")))
                .andExpect(jsonPath("$.data.groups[0].rows[1].values[0].option.label.value").value("Free zone"))
                .andExpect(jsonPath("$.data.groups[1].rows[*].attribute").value(contains("country", "province")))
                .andExpect(jsonPath("$.data.groups[1].rows[0].values[0].option.label.value").value("Iran"))
                .andExpect(jsonPath("$.data.groups[1].rows[1].valueType").value("SUBJECT_REF"))
                .andExpect(jsonPath("$.data.groups[1].rows[1].values[0].subject.id").value(provinceId.toString()))
                .andExpect(jsonPath("$.data.groups[1].rows[1].values[0].subject.name.value").value("Hormozgan"))
                .andExpect(jsonPath("$.data.groups[1].rows[1].values[0].subject.slug").value(provinceArticle.slug()))
                .andExpect(jsonPath("$.data.groups[2].rows[*].attribute")
                        .value(contains("population", "population_density")))
                .andExpect(jsonPath("$.data.groups[2].rows[0].values.length()").value(1))
                .andExpect(jsonPath("$.data.groups[2].rows[0].values[0].number").value(148993))
                .andExpect(jsonPath("$.data.groups[2].rows[0].values[0].asOf").value("2016-01-01"))
                .andExpect(jsonPath("$.data.groups[2].rows[0].values[0].sourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$.data.groups[2].rows[0].isDerived").value(false))
                .andExpect(jsonPath("$.data.groups[2].rows[1].isDerived").value(true))
                .andExpect(jsonPath("$.data.groups[2].rows[1].label.value").value("Population density"))
                .andExpect(jsonPath("$.data.groups[2].rows[1].unit").value("per_km2"))
                .andExpect(jsonPath("$.data.groups[2].rows[1].values[0].number").value(99.93))
                .andExpect(jsonPath("$.data.groups[2].rows[1].values[0].asOf").value("2016-01-01"))
                .andExpect(jsonPath("$.data.groups[2].rows[1].values[0].sourceId").doesNotExist())
                .andExpect(jsonPath("$.data.revision.number").value(1))
                .andExpect(jsonPath("$.data.revision.verification.state").value("NONE"));

        infobox(islandId, "fa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name.value").value("جزیرهٔ قشم"))
                .andExpect(jsonPath("$.data.groups[0].label.value").value("جغرافیا"))
                .andExpect(jsonPath("$.data.groups[1].rows[1].values[0].subject.name.value").value("هرمزگان"));

        // No Arabic name: the Farsi one stands in, marked.
        infobox(islandId, "ar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name.value").value("جزیرهٔ قشم"))
                .andExpect(jsonPath("$.data.name.language").value("fa"))
                .andExpect(jsonPath("$.data.name.isFallback").value(true));
    }

    // Each of these writes changes what a cached infobox shows, and each must be visible on the
    // very next read: an expert's mark, the first approval of a referenced subject's article, and
    // a newer revision of the subject itself, which leaves the earlier mark STALE.
    @Test
    void cachedInfoboxFollowsVerificationArticlePublicationAndNewerRevisions() throws Exception {
        Session author = signIn("rsfauth2");
        Session moderator = signIn("rsfmod2", Role.MODERATOR);
        Session expert = expert("rsfexp2", SubjectKind.ISLAND);
        UUID provinceId = province(author, moderator, "بوشهر", "Bushehr");
        UUID firstRevisionId = propose(author, "ISLAND", payload("خارک", "Kharg", reference("province", provinceId)));
        publish(author, moderator, firstRevisionId);
        UUID islandId = subjectRevisionRepository.findById(firstRevisionId).orElseThrow().getSubjectId();

        infobox(islandId, "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.verification.state").value("NONE"))
                .andExpect(jsonPath("$.data.groups[0].rows[0].values[0].subject.slug").doesNotExist());

        send(post("/api/subject-revisions/" + firstRevisionId + "/verifications"), expert, null)
                .andExpect(status().isCreated());
        infobox(islandId, "en")
                .andExpect(jsonPath("$.data.revision.verification.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.revision.verification.verifiedRevisionNumber").value(1));

        PublishedArticle provinceArticle = publishedArticle(author, moderator, provinceId);
        infobox(islandId, "en")
                .andExpect(jsonPath("$.data.groups[0].rows[0].values[0].subject.slug").value(provinceArticle.slug()));

        UUID secondRevisionId = startEdit(author, islandId,
                payload("خارک", "Kharg Island", reference("province", provinceId)));
        publish(author, moderator, secondRevisionId);
        infobox(islandId, "en")
                .andExpect(jsonPath("$.data.name.value").value("Kharg Island"))
                .andExpect(jsonPath("$.data.revision.number").value(2))
                .andExpect(jsonPath("$.data.revision.verification.state").value("STALE"))
                .andExpect(jsonPath("$.data.revision.verification.verifiedRevisionNumber").value(1));
    }

    // The main image only while it is a public image; a subject nobody has approved a revision of
    // has an empty infobox rather than an error; an unknown id is a 404.
    @Test
    void mainImageShowsOnlyWhilePublicAndUnpublishedSubjectsHaveEmptyInfoboxes() throws Exception {
        Session author = signIn("rsfauth3");
        Session moderator = signIn("rsfmod3", Role.MODERATOR);
        UUID provinceId = province(author, moderator, "خوزستان", "Khuzestan");
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("مینو", "Minu", reference("province", provinceId)));
        PublishedArticle article = publishedArticle(author, moderator, islandId);
        UUID imageId = seedMedia(article.id(), author.userId(), MediaKind.IMAGE, PublicationStatus.PUBLISHED);
        publish(author, moderator, startEdit(author, islandId, """
                {"names": {"fa": "مینو", "en": "Minu"},
                 "facts": [%s], "mainImageMediaId": "%s"}""".formatted(reference("province", provinceId), imageId)));

        // Both carry what it takes to show the image, with its permanent public URLs.
        String publicUrl = "http://localhost/test-media/public/" + imageId + "/";
        infobox(islandId, "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainImage.mediaId").value(imageId.toString()))
                .andExpect(jsonPath("$.data.mainImage.placeholder").value("data:image/webp;base64,UklGR"))
                .andExpect(jsonPath("$.data.mainImage.variants[*].url")
                        .value(containsInAnyOrder(publicUrl + "640.webp", publicUrl + "1280.webp")));
        String listed = listingBody("kind", "ISLAND", "attr.province", provinceId.toString());
        assertThat(JsonPath.<String>read(listed, "$.data.items[0].mainImage.mediaId"))
                .isEqualTo(imageId.toString());
        assertThat(JsonPath.<String>read(listed, "$.data.items[0].mainImage.placeholder"))
                .isEqualTo("data:image/webp;base64,UklGR");
        assertThat(JsonPath.<List<String>>read(listed, "$.data.items[0].mainImage.variants[*].url"))
                .containsExactlyInAnyOrder(publicUrl + "640.webp", publicUrl + "1280.webp");
        assertThat(JsonPath.<List<Integer>>read(listed, "$.data.items[0].mainImage.variants[*].width"))
                .containsExactlyInAnyOrder(640, 1280);
        assertThat(JsonPath.<String>read(listed, "$.data.items[0].slug")).isEqualTo(article.slug());
        assertThat(JsonPath.<Integer>read(listed, "$.data.total")).isEqualTo(1);
        assertThat(JsonPath.<Map<String, String>>read(listed, "$.data.items[0].badges")).isEmpty();

        // Back to awaiting review: dropped too, rather than handed out with signed URLs.
        ArticleMedia pending = articleMediaRepository.findById(imageId).orElseThrow();
        pending.setPublicationStatus(PublicationStatus.PENDING);
        articleMediaRepository.save(pending);
        listing("en", "kind", "ISLAND", "attr.province", provinceId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mainImage").doesNotExist());

        // Hidden after the fact: the listing, which is not cached, stops offering it at once.
        ArticleMedia image = articleMediaRepository.findById(imageId).orElseThrow();
        image.setPublicationStatus(PublicationStatus.HIDDEN);
        articleMediaRepository.save(image);
        listing("en", "kind", "ISLAND", "attr.province", provinceId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mainImage").doesNotExist());

        Subject unpublished = subjectRepository.save(Subject.builder().kind(SubjectKind.PORT).build());
        infobox(unpublished.getId(), "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("PORT"))
                .andExpect(jsonPath("$.data.name").doesNotExist())
                .andExpect(jsonPath("$.data.groups").isEmpty())
                .andExpect(jsonPath("$.data.revision").doesNotExist());

        infobox(UUID.randomUUID(), "en")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    // --- Smoke: the facet listing ---

    // OR within a field, AND across fields, and each field's counts taken with every other field's
    // filter applied but not its own. Every option is listed, zero counts included.
    @Test
    void listingFiltersDisjunctivelyAndCountsEachFacetWithoutItsOwnFilter() throws Exception {
        Session author = signIn("rsfauth4");
        Session moderator = signIn("rsfmod4", Role.MODERATOR);
        UUID provinceId = province(author, moderator, "استان آزمون", "Test Province");
        String province = reference("province", provinceId);
        UUID a = approvedSubject(author, moderator, "ISLAND",
                payload("الف", "A", province, options("country", "IR"), options("place_features", "FREE_ZONE")));
        UUID b = approvedSubject(author, moderator, "ISLAND",
                payload("ب", "B", province, options("country", "IR", "OM"), options("place_features", "UNESCO_GEOPARK")));
        UUID c = approvedSubject(author, moderator, "ISLAND", payload("پ", "C", province, options("country", "OM")));
        UUID d = approvedSubject(author, moderator, "PORT",
                payload("ت", "D", province, options("country", "IR"), options("place_features", "FREE_ZONE")));

        String all = listingBody("kind", "ISLAND,PORT", "attr.province", provinceId.toString());
        assertThat(JsonPath.<Integer>read(all, "$.data.total")).isEqualTo(4);
        // Newest first.
        assertThat(itemIds(all)).containsExactly(d.toString(), c.toString(), b.toString(), a.toString());
        assertThat(JsonPath.<List<String>>read(all, "$.data.facets[*].key"))
                .containsSubsequence("kind", "place_features", "country", "province", "has_360_tour")
                .doesNotContain("population", "county", "iucn_status");
        assertThat(facetCount(all, "country", "IR")).isEqualTo(3);
        assertThat(facetCount(all, "country", "OM")).isEqualTo(2);
        assertThat(facetCount(all, "country", "KW")).isEqualTo(0);
        assertThat(JsonPath.<List<String>>read(all, "$.data.facets[?(@.key == 'country')].options[*].code"))
                .containsExactly("IR", "IQ", "KW", "SA", "BH", "QA", "AE", "OM");
        assertThat(facetCount(all, "province", provinceId.toString())).isEqualTo(4);
        assertThat(JsonPath.<List<Object>>read(all, "$.data.facets[?(@.key == 'kind')].label")).containsExactly((Object) null);
        assertThat(JsonPath.<List<String>>read(all, "$.data.items[?(@.id == '%s')].name.value".formatted(a)))
                .containsExactly("A");

        // AND across fields; the country facet ignores its own selection.
        String oman = listingBody("kind", "ISLAND,PORT", "attr.province", provinceId.toString(), "attr.country", "OM");
        assertThat(itemIds(oman)).containsExactlyInAnyOrder(b.toString(), c.toString());
        assertThat(facetCount(oman, "country", "IR")).isEqualTo(3);
        assertThat(facetCount(oman, "place_features", "FREE_ZONE")).isEqualTo(0);
        assertThat(facetCount(oman, "place_features", "UNESCO_GEOPARK")).isEqualTo(1);
        assertThat(facetCount(oman, "kind", "ISLAND")).isEqualTo(2);
        assertThat(facetCount(oman, "kind", "PORT")).isEqualTo(0);

        // OR within a field, values repeated or comma-separated, codes in any case; the kind facet
        // counts every kind whatever `kind` selects.
        String islands = listingBody("kind", "ISLAND", "attr.province", provinceId.toString(),
                "attr.country", "ir", "attr.place_features", "FREE_ZONE", "attr.place_features", "UNESCO_GEOPARK");
        assertThat(itemIds(islands)).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(facetCount(islands, "kind", "ISLAND")).isEqualTo(2);
        assertThat(facetCount(islands, "kind", "PORT")).isEqualTo(1);
        assertThat(facetCount(islands, "place_features", "FREE_ZONE")).isEqualTo(1);

        // Paging leaves the total and the facets alone.
        String secondPage = listingBody("kind", "ISLAND,PORT", "attr.province", provinceId.toString(),
                "page", "1", "size", "3");
        assertThat(itemIds(secondPage)).containsExactly(a.toString());
        assertThat(JsonPath.<Integer>read(secondPage, "$.data.total")).isEqualTo(4);
        assertThat(facetCount(secondPage, "country", "IR")).isEqualTo(3);
    }

    // has_360_tour is computed, never stored: a public 360° panorama in the gallery of the
    // subject's main article -- the first one published, not just any of them.
    @Test
    void has360TourCountsOnlyPublicPanoramasOfTheMainArticle() throws Exception {
        Session author = signIn("rsfauth5");
        Session moderator = signIn("rsfmod5", Role.MODERATOR);
        UUID provinceId = province(author, moderator, "استان پانوراما", "Panorama Province");
        String province = reference("province", provinceId);
        UUID toured = approvedSubject(author, moderator, "ISLAND", payload("گردش", "Toured", province));
        UUID pending = approvedSubject(author, moderator, "ISLAND", payload("در انتظار", "Pending", province));
        UUID secondary = approvedSubject(author, moderator, "PORT", payload("دومی", "Secondary", province));

        seedMedia(publishedArticle(author, moderator, toured).id(), author.userId(), MediaKind.PANORAMA_360,
                PublicationStatus.PUBLISHED);
        seedMedia(publishedArticle(author, moderator, pending).id(), author.userId(), MediaKind.PANORAMA_360,
                PublicationStatus.PENDING);
        // The port's main article has no panorama; a newer article of it does.
        publishedArticle(author, moderator, secondary);
        seedMedia(publishedArticle(author, moderator, secondary).id(), author.userId(), MediaKind.PANORAMA_360,
                PublicationStatus.PUBLISHED);

        String filtered = listingBody("kind", "ISLAND,PORT", "attr.province", provinceId.toString(),
                "attr.has_360_tour", "TRUE");
        assertThat(itemIds(filtered)).containsExactly(toured.toString());
        assertThat(JsonPath.<List<String>>read(filtered, "$.data.facets[?(@.key == 'has_360_tour')].options[*].code"))
                .containsExactly("true");
        assertThat(facetCount(filtered, "has_360_tour", "true")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(filtered,
                "$.data.facets[?(@.key == 'has_360_tour')].options[0].label.value")).containsExactly("Has a 360° tour");
        assertThat(facetCount(filtered, "kind", "PORT")).isEqualTo(0);
    }

    // Badges are each badge field's code; a species listing offers the species fields in
    // vocabulary order.
    @Test
    void speciesCardsCarryTheirBadgesAndTheSpeciesFacetsAreOffered() throws Exception {
        Session author = signIn("rsfauth6");
        Session moderator = signIn("rsfmod6", Role.MODERATOR);
        UUID tuna = approvedSubject(author, moderator, "SPECIES", payload("ماهی هوور", "Longtail tuna",
                text("scientific_name", "Thunnus tonggol"), options("category", "FISH"),
                options("iucn_status", "EN")));

        String body = listingBody("kind", "species", "attr.iucn_status", "EN", "size", "100");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.id == '%s')].badges.iucn_status"
                .formatted(tuna))).containsExactly("EN");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.id == '%s')].slug".formatted(tuna)))
                .containsExactly((String) null);
        assertThat(JsonPath.<List<String>>read(body, "$.data.facets[*].key"))
                .containsSubsequence("kind", "category", "iucn_status", "habitat", "species_features", "has_360_tour")
                .doesNotContain("country", "max_length");
        assertThat(JsonPath.<List<String>>read(body, "$.data.facets[?(@.key == 'iucn_status')].options[*].code"))
                .containsExactly("CR", "EN", "VU", "NT", "LC", "DD");
        assertThat(JsonPath.<List<String>>read(body,
                "$.data.facets[?(@.key == 'iucn_status')].options[?(@.code == 'EN')].label.value"))
                .containsExactly("Endangered");
        assertThat((Integer) facetCount(body, "iucn_status", "EN")).isPositive();
    }

    // Every bad field filter is reported at once, against the parameter that carries it.
    @Test
    void badFieldFiltersAreReportedPerParameter() throws Exception {
        Subject island = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build());

        listing("en", "attr.country", "IR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(contains("kind")));

        listing("en", "kind", "ISLAND",
                "attr.no_such_field", "X",
                "attr.iucn_status", "EN",
                "attr.population", "5",
                "attr.country", "IR,ZZ",
                "attr.province", "not-a-uuid," + island.getId(),
                "attr.has_360_tour", "false")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("attr.no_such_field",
                        "attr.iucn_status", "attr.population", "attr.country", "attr.province", "attr.province",
                        "attr.has_360_tour")))
                .andExpect(jsonPath("$.errors[?(@.field == 'attr.country')].message")
                        .value(contains("The option ZZ is not defined for this field.")))
                .andExpect(jsonPath("$.errors[?(@.field == 'attr.iucn_status')].message")
                        .value(contains("The field iucn_status is not defined for any of the selected subject kinds.")));

        // An empty filter selects nothing, so it filters nothing.
        listing("en", "kind", "ISLAND", "attr.country", "").andExpect(status().isOk());
    }

    // Only approval writes the read model: an edit awaiting review changes neither the listing nor
    // the infobox.
    @Test
    void pendingRevisionsChangeNeitherTheListingNorTheInfobox() throws Exception {
        Session author = signIn("rsfauth7");
        Session moderator = signIn("rsfmod7", Role.MODERATOR);
        UUID provinceId = province(author, moderator, "استان در انتظار", "Waiting Province");
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("لاوان", "Lavan", reference("province", provinceId), options("country", "IR")));
        submit(author, startEdit(author, islandId,
                payload("لاوان", "Lavan", reference("province", provinceId), options("country", "OM"))));

        String oman = listingBody("kind", "ISLAND", "attr.province", provinceId.toString(), "attr.country", "OM");
        assertThat(itemIds(oman)).isEmpty();
        assertThat(facetCount(oman, "country", "IR")).isEqualTo(1);
        assertThat(facetCount(oman, "country", "OM")).isEqualTo(0);
        infobox(islandId, "en")
                .andExpect(jsonPath("$.data.groups[0].rows[0].values[*].option.code").value(contains("IR")))
                .andExpect(jsonPath("$.data.revision.number").value(1));
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // Derived fields join the vocabulary, but no payload may carry one (decision 14).
    @Test
    void payloadCarryingADerivedFieldIsRejected() throws Exception {
        Session author = signIn("rsfauth8");
        UUID sourceId = seedSource();

        send(post("/api/subject-revisions"), author, """
                {"kind": "ISLAND", "payload": %s}""".formatted(payload("ناژو", null,
                number("population_density", 12, sourceId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(contains("payload.facts[0].attribute")));
    }

    // A label written by an admin reaches an infobox that was cached before the write.
    @Test
    void adminLabelChangeReachesACachedInfobox() throws Exception {
        Session author = signIn("rsfauth9");
        Session moderator = signIn("rsfmod9", Role.MODERATOR);
        Session admin = signIn("rsfadmin9", Role.ADMIN);
        // A field of this test's own, so relabelling it leaves the shared vocabulary alone.
        AttributeDefinition nickname = AttributeDefinition.builder()
                .key("t_nick_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .valueType(AttributeValueType.PLAIN_TEXT)
                .groupId(attributeGroupRepository.findFirstByOrderBySortAscKeyAsc().orElseThrow().getId())
                .isInfobox(true)
                .build();
        nickname.replaceKinds(Set.of(SubjectKind.ISLAND));
        nickname = attributeDefinitionRepository.save(nickname);
        labelRepository.save(Label.builder().attributeId(nickname.getId()).language("fa").value("لقب").build());
        labelRepository.save(Label.builder().attributeId(nickname.getId()).language("en").value("Nickname").build());
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("هندورابی", "Hendurabi", text(nickname.getKey(), "Hendorabi")));
        String rowLabel = "$.data.groups[*].rows[?(@.attribute == '%s')].label.value".formatted(nickname.getKey());

        infobox(islandId, "en").andExpect(jsonPath(rowLabel).value(contains("Nickname")));
        send(put("/api/admin/labels/ATTRIBUTE/" + nickname.getId() + "/en"), admin, """
                {"value": "Byname"}""")
                .andExpect(status().isOk());
        infobox(islandId, "en").andExpect(jsonPath(rowLabel).value(contains("Byname")));
    }

    // The plain subject read and its protection rules are unchanged by the new routes beside it.
    @Test
    void subjectReadIsUnchangedAndTheNewRoutesOpenNoWrites() throws Exception {
        Subject port = subjectRepository.save(Subject.builder().kind(SubjectKind.PORT).build());

        mockMvc.perform(get("/api/subjects/" + port.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("PORT"));
        mockMvc.perform(post("/api/subjects/" + port.getId() + "/infobox").with(xsrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/subjects").param("kind", "PORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(port.getId().toString())));
    }
}

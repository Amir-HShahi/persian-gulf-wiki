package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 6: linking subjects and articles -- the main article and its switch, a subject's article
// list, subject links in article bodies, and back-references through SUBJECT_REF facts. Subjects
// and articles go through the real proposal, article and moderation routes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectArticleFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String PLAIN_BODY = """
            {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "متن"}]}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private SubjectRevisionRepository subjectRevisionRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    @Autowired
    private ArticleRevisionRepository articleRevisionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // --- Helpers ---

    private record Session(UUID userId, Cookie access, Cookie csrf) {
    }

    private record DraftArticle(UUID id, UUID revisionId, String slug) {
    }

    private Session signIn(String name, Role... roles) throws Exception {
        String email = name + "@saf.example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(name, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        for (Role role : roles) {
            userRoleRepository.save(UserRole.builder().user(user).role(role).build());
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

    private void approve(Session moderator, UUID taskId) throws Exception {
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null)))
                .andExpect(status().isOk());
    }

    private UUID approvedSubject(Session author, Session moderator, String kind, String payload) throws Exception {
        String body = send(post("/api/subject-revisions"), author, """
                {"kind": "%s", "payload": %s}""".formatted(kind, payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        send(post("/api/subject-revisions/" + revisionId + "/submit"), author, null).andExpect(status().isOk());
        approve(moderator, moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow().getId());
        return subjectRevisionRepository.findById(revisionId).orElseThrow().getSubjectId();
    }

    private ResultActions postArticle(Session author, @Nullable UUID subjectId, String language, String slug,
            String body) throws Exception {
        String subject = subjectId == null ? "null" : "\"" + subjectId + "\"";
        return send(post("/api/articles"), author, """
                {"subjectId": %s, "canonicalLanguage": "%s", "slug": "%s", "title": "عنوان %s", "body": %s}"""
                .formatted(subject, language, slug, language, body));
    }

    private DraftArticle draftArticle(Session author, @Nullable UUID subjectId, String language, String body)
            throws Exception {
        String slug = "saf-" + UUID.randomUUID();
        String created = postArticle(author, subjectId, language, slug, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        return new DraftArticle(articleId, firstRevisionId(author, articleId, language), slug);
    }

    private UUID firstRevisionId(Session author, UUID articleId, String language) throws Exception {
        String path = "/api/articles/" + articleId + "/translations/" + language + "/revisions";
        String revisions = mockMvc.perform(get(path).cookie(author.access()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
    }

    private void publishRevision(Session author, Session moderator, UUID articleId, String language, UUID revisionId)
            throws Exception {
        send(post("/api/articles/" + articleId + "/translations/" + language + "/revisions/" + revisionId + "/submit"),
                author, null)
                .andExpect(status().isOk());
        approve(moderator, moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId());
    }

    private DraftArticle publishedArticle(Session author, Session moderator, @Nullable UUID subjectId, String language,
            String body) throws Exception {
        DraftArticle article = draftArticle(author, subjectId, language, body);
        publishRevision(author, moderator, article.id(), language, article.revisionId());
        return article;
    }

    private DraftArticle publishedArticle(Session author, Session moderator, UUID subjectId) throws Exception {
        return publishedArticle(author, moderator, subjectId, "fa", PLAIN_BODY);
    }

    private void publishTranslation(Session author, Session moderator, UUID articleId, String language)
            throws Exception {
        send(post("/api/articles/" + articleId + "/translations"), author, """
                {"language": "%s", "slug": "saf-%s", "title": "Title %s", "body": %s}"""
                .formatted(language, UUID.randomUUID(), language, PLAIN_BODY))
                .andExpect(status().isCreated());
        publishRevision(author, moderator, articleId, language, firstRevisionId(author, articleId, language));
    }

    private ResultActions subjectArticles(UUID subjectId, String language) throws Exception {
        return mockMvc.perform(get("/api/subjects/" + subjectId + "/articles")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language));
    }

    private ResultActions switchMain(Session session, UUID subjectId, String json) throws Exception {
        return send(patch("/api/subjects/" + subjectId + "/main-article"), session, json);
    }

    private static String articleIdBody(UUID articleId) {
        return "{\"articleId\": \"%s\"}".formatted(articleId);
    }

    private static String payload(String fa, @Nullable String en, String... facts) {
        String names = en == null ? "{\"fa\": \"%s\"}".formatted(fa)
                : "{\"fa\": \"%s\", \"en\": \"%s\"}".formatted(fa, en);
        return "{\"names\": " + names + ", \"facts\": [" + String.join(", ", facts) + "]}";
    }

    private static String reference(String attribute, UUID subjectId) {
        return "{\"attribute\": \"%s\", \"values\": [{\"subjectId\": \"%s\"}]}".formatted(attribute, subjectId);
    }

    // A paragraph holding the given inline nodes after a text node, so a link sits at
    // body.content[0].content[1] onwards.
    private static String bodyWith(String... inlineNodes) {
        return """
                {"type": "doc", "content": [{"type": "paragraph",
                 "content": [{"type": "text", "text": "ببینید "}, %s]}]}"""
                .formatted(String.join(", ", inlineNodes));
    }

    private static String link(Object subjectId) {
        return "{\"type\": \"subjectLink\", \"subjectId\": \"%s\"}".formatted(subjectId);
    }

    private List<String> provinceSlugInInfoboxOf(UUID subjectId) throws Exception {
        String infobox = mockMvc.perform(get("/api/subjects/" + subjectId + "/infobox"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(infobox, "$.data.groups[*].rows[?(@.attribute == 'province')].values[0].subject.slug");
    }

    private long switchAuditRows(UUID articleId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> "SUBJECT_MAIN_ARTICLE_SWITCH".equals(row.getAction()))
                .map(AuditLog::getDetail)
                .filter(detail -> detail.endsWith("to=" + articleId))
                .count();
    }

    // --- Smoke: the main article ---

    // The first article to be published becomes main and lists first; a later one does not, and a
    // draft is neither main nor listed. The subject's card links to the main article.
    @Test
    void firstPublishedArticleBecomesMainAndListsFirst() throws Exception {
        Session author = signIn("safauth1");
        Session moderator = signIn("safmod1", Role.MODERATOR);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان یکم", "First Province"));
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("جزیرهٔ لارک", "Larak Island", reference("province", provinceId)));
        DraftArticle draft = draftArticle(author, islandId, "fa", PLAIN_BODY);
        DraftArticle first = publishedArticle(author, moderator, islandId);
        DraftArticle second = publishedArticle(author, moderator, islandId);

        assertThat(articleRepository.findById(first.id()).orElseThrow().isMain()).isTrue();
        assertThat(articleRepository.findById(second.id()).orElseThrow().isMain()).isFalse();
        assertThat(articleRepository.findById(draft.id()).orElseThrow().isMain()).isFalse();

        subjectArticles(islandId, "fa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].articleId")
                        .value(contains(first.id().toString(), second.id().toString())))
                .andExpect(jsonPath("$.data[*].isMain").value(contains(true, false)))
                .andExpect(jsonPath("$.data[0].slug").value(first.slug()))
                .andExpect(jsonPath("$.data[0].title").value("عنوان fa"))
                .andExpect(jsonPath("$.data[0].language").value("fa"))
                .andExpect(jsonPath("$.data[0].isFallback").value(false))
                .andExpect(jsonPath("$.data[0].revisionId").value(first.revisionId().toString()));

        mockMvc.perform(get("/api/subjects").param("kind", "ISLAND").param("attr.province", provinceId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(contains(islandId.toString())))
                .andExpect(jsonPath("$.data.items[0].slug").value(first.slug()));
    }

    // Each article through one approved translation: the reader's language, else Farsi, else the
    // article's own, flagged whenever it is not the reader's.
    @Test
    void subjectArticlesAreShownInTheReadersLanguageElseFarsiElseTheirOwn() throws Exception {
        Session author = signIn("safauth2");
        Session moderator = signIn("safmod2", Role.MODERATOR);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ هندورابی", "Hendurabi Island"));
        DraftArticle bilingual = publishedArticle(author, moderator, islandId);
        publishTranslation(author, moderator, bilingual.id(), "en");
        DraftArticle englishOnly = publishedArticle(author, moderator, islandId, "en", PLAIN_BODY);

        subjectArticles(islandId, "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].language").value(contains("en", "en")))
                .andExpect(jsonPath("$.data[*].isFallback").value(contains(false, false)))
                .andExpect(jsonPath("$.data[0].title").value("Title en"));
        subjectArticles(islandId, "ar")
                .andExpect(jsonPath("$.data[*].articleId")
                        .value(contains(bilingual.id().toString(), englishOnly.id().toString())))
                .andExpect(jsonPath("$.data[*].language").value(contains("fa", "en")))
                .andExpect(jsonPath("$.data[*].isFallback").value(contains(true, true)));
        subjectArticles(islandId, "fa")
                .andExpect(jsonPath("$.data[*].isFallback").value(contains(false, true)));
        // Paged: the second page of one holds the non-main article.
        mockMvc.perform(get("/api/subjects/" + islandId + "/articles").param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.data[*].articleId").value(contains(englishOnly.id().toString())));
    }

    // The switch moves the province's page, so an island's cached infobox linking to it must follow.
    @Test
    void moderatorSwitchesTheMainArticleAndLinksToTheSubjectFollow() throws Exception {
        Session author = signIn("safauth3");
        Session moderator = signIn("safmod3", Role.MODERATOR);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان سوم", "Third Province"));
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("جزیرهٔ شیدور", "Sheedvar Island", reference("province", provinceId)));
        DraftArticle first = publishedArticle(author, moderator, provinceId);
        DraftArticle second = publishedArticle(author, moderator, provinceId);
        assertThat(provinceSlugInInfoboxOf(islandId)).containsExactly(first.slug());

        switchMain(moderator, provinceId, articleIdBody(second.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleId").value(second.id().toString()))
                .andExpect(jsonPath("$.data.isMain").value(true))
                .andExpect(jsonPath("$.data.slug").value(second.slug()));

        assertThat(articleRepository.findById(first.id()).orElseThrow().isMain()).isFalse();
        assertThat(articleRepository.findById(second.id()).orElseThrow().isMain()).isTrue();
        assertThat(switchAuditRows(second.id())).isEqualTo(1);
        subjectArticles(provinceId, "fa")
                .andExpect(jsonPath("$.data[*].articleId")
                        .value(contains(second.id().toString(), first.id().toString())));
        assertThat(provinceSlugInInfoboxOf(islandId)).containsExactly(second.slug());

        // Picking the main article again changes nothing and logs nothing.
        switchMain(moderator, provinceId, articleIdBody(second.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMain").value(true));
        assertThat(switchAuditRows(second.id())).isEqualTo(1);
    }

    @Test
    void onlyModeratorsMaySwitchTheMainArticle() throws Exception {
        Session author = signIn("safauth4");
        Session moderator = signIn("safmod4", Role.MODERATOR);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ فارور", "Farur Island"));
        DraftArticle first = publishedArticle(author, moderator, islandId);
        DraftArticle second = publishedArticle(author, moderator, islandId);

        switchMain(author, islandId, articleIdBody(second.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(patch("/api/subjects/" + islandId + "/main-article")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleIdBody(second.id())))
                .andExpect(status().isUnauthorized());
        // A moderator's request without the CSRF header never reaches the switch.
        mockMvc.perform(patch("/api/subjects/" + islandId + "/main-article")
                        .cookie(moderator.access(), moderator.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleIdBody(second.id())))
                .andExpect(status().isForbidden());

        assertThat(articleRepository.findById(first.id()).orElseThrow().isMain()).isTrue();
        assertThat(articleRepository.findById(second.id()).orElseThrow().isMain()).isFalse();
    }

    @Test
    void anArticleThatCannotBeMainIsRejectedOnArticleId() throws Exception {
        Session author = signIn("safauth5");
        Session moderator = signIn("safmod5", Role.MODERATOR);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ نخیلو", "Nakhiloo Island"));
        UUID otherIslandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ ام‌الکرم", "Omolkaram"));
        DraftArticle main = publishedArticle(author, moderator, islandId);
        DraftArticle draft = draftArticle(author, islandId, "fa", PLAIN_BODY);
        DraftArticle otherSubjects = publishedArticle(author, moderator, otherIslandId);

        for (UUID articleId : List.of(draft.id(), otherSubjects.id(), UUID.randomUUID())) {
            switchMain(moderator, islandId, articleIdBody(articleId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field").value(contains("articleId")));
        }
        switchMain(moderator, islandId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(contains("articleId")));
        switchMain(moderator, UUID.randomUUID(), articleIdBody(main.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));

        assertThat(articleRepository.findById(main.id()).orElseThrow().isMain()).isTrue();
        assertThat(articleRepository.findById(otherSubjects.id()).orElseThrow().isMain()).isTrue();
    }

    @Test
    void articlesOfAnUnknownSubjectAreNotFound() throws Exception {
        mockMvc.perform(get("/api/subjects/" + UUID.randomUUID() + "/articles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    // --- Smoke: subject links in article bodies ---

    // Checked on every save -- a new article, a new translation and a draft edit -- and reported at
    // each bad link's path, in document order. Nothing is written when one fails.
    @Test
    void subjectLinksMustNameExistingSubjects() throws Exception {
        Session author = signIn("safauth6");
        Session moderator = signIn("safmod6", Role.MODERATOR);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ کیش", "Kish Island"));
        String rejectedSlug = "saf-" + UUID.randomUUID();

        postArticle(author, null, "fa", rejectedSlug,
                bodyWith(link(islandId), link(UUID.randomUUID()), link("not-a-uuid"), "{\"type\": \"subjectLink\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(contains("body.content[0].content[2].subjectId",
                        "body.content[0].content[3].subjectId", "body.content[0].content[4].subjectId")));
        assertThat(articleTranslationRepository.existsBySlug(rejectedSlug)).isFalse();

        String tooMany = bodyWith(String.join(", ", Collections.nCopies(1001, link(islandId))));
        postArticle(author, null, "fa", rejectedSlug, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("body")));

        DraftArticle article = draftArticle(author, null, "fa", bodyWith(link(islandId)));
        send(patch("/api/articles/" + article.id() + "/translations/fa/revisions/" + article.revisionId()), author, """
                {"title": "عنوان", "body": %s}""".formatted(bodyWith(link(UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("body.content[0].content[1].subjectId")));
        assertThat(articleRevisionRepository.findById(article.revisionId()).orElseThrow().getBody())
                .contains(islandId.toString());

        send(post("/api/articles/" + article.id() + "/translations"), author, """
                {"language": "en", "slug": "saf-%s", "title": "Title", "body": %s}"""
                .formatted(UUID.randomUUID(), bodyWith(link(UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("body.content[0].content[1].subjectId")));
        assertThat(articleTranslationRepository.findByArticleIdAndLanguage(article.id(), "en")).isEmpty();
    }

    // The body keeps only the id; every read names the subject in the reader's language and gives
    // its page's slug, null when it has no published article.
    @Test
    void subjectLinksAreNamedInTheReadersLanguageWithTheLinkedPage() throws Exception {
        Session author = signIn("safauth7");
        Session moderator = signIn("safmod7", Role.MODERATOR);
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ هرمز", "Hormuz Island"));
        DraftArticle islandPage = publishedArticle(author, moderator, islandId);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان بی‌صفحه", null));
        DraftArticle article = publishedArticle(author, moderator, null, "fa",
                bodyWith(link(islandId), link(provinceId), link(islandId)));

        String revision = "/api/articles/" + article.id() + "/translations/fa/revisions/" + article.revisionId();
        mockMvc.perform(get(revision).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body.content[0].content[1].subjectId").value(islandId.toString()))
                .andExpect(jsonPath("$.data.subjectLinks.length()").value(2))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].kind".formatted(islandId)).value("ISLAND"))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.value".formatted(islandId)).value("Hormuz Island"))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.isFallback".formatted(islandId)).value(false))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].slug".formatted(islandId)).value(islandPage.slug()))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.value".formatted(provinceId))
                        .value("استان بی‌صفحه"))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.isFallback".formatted(provinceId)).value(true))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].slug".formatted(provinceId)).doesNotExist());
        mockMvc.perform(get(revision).header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.value".formatted(islandId)).value("جزیرهٔ هرمز"))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.language".formatted(islandId)).value("fa"));
        mockMvc.perform(get("/api/articles/" + article.id() + "/translations/fa/revisions")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.data[0].subjectLinks['%s'].name.value".formatted(islandId))
                        .value("Hormuz Island"));
    }

    // Each listed revision carries only the links its own body holds. The API gives a translation one
    // revision today, so the second is seeded.
    @Test
    void listedRevisionsEachCarryOnlyTheirOwnLinks() throws Exception {
        Session author = signIn("safauth11");
        Session moderator = signIn("safmod11", Role.MODERATOR);
        UUID firstLinked = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ نخست", "First Linked"));
        UUID secondLinked = approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ دوم", "Second Linked"));
        DraftArticle article = publishedArticle(author, moderator, null, "fa", bodyWith(link(firstLinked)));
        articleRevisionRepository.save(ArticleRevision.builder()
                .translationId(articleTranslationRepository.findByArticleIdAndLanguage(article.id(), "fa")
                        .orElseThrow().getId())
                .revisionNumber(2)
                .parentRevisionId(article.revisionId())
                .title("عنوان دوم")
                .body(bodyWith(link(secondLinked)))
                .status(RevisionStatus.APPROVED)
                .authorId(author.userId())
                .build());

        mockMvc.perform(get("/api/articles/" + article.id() + "/translations/fa/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].revisionNumber").value(contains(1, 2)))
                .andExpect(jsonPath("$.data[0].subjectLinks.length()").value(1))
                .andExpect(jsonPath("$.data[0].subjectLinks['%s']".formatted(firstLinked)).exists())
                .andExpect(jsonPath("$.data[1].subjectLinks.length()").value(1))
                .andExpect(jsonPath("$.data[1].subjectLinks['%s']".formatted(secondLinked)).exists());
    }

    // --- Regression: revisions without links ---

    // A body with no links reads back unchanged, with an empty link map.
    @Test
    void aRevisionWithoutLinksReadsBackUnchangedWithNoLinks() throws Exception {
        Session author = signIn("safauth8");
        DraftArticle article = draftArticle(author, null, "fa", PLAIN_BODY);

        mockMvc.perform(get("/api/articles/" + article.id() + "/translations/fa/revisions/" + article.revisionId())
                        .cookie(author.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body.content[0].content[0].text").value("متن"))
                .andExpect(jsonPath("$.data.subjectLinks").isEmpty());
        assertThat(articleRepository.findById(article.id()).orElseThrow().isMain()).isFalse();
    }

    // --- Smoke: back-references ---

    // Only PREFERRED values point here: an island holding this province as a NORMAL value, next to
    // a preferred other province, is left out.
    @Test
    void referencedByListsTheSubjectsWhosePreferredReferencePointsHere() throws Exception {
        Session author = signIn("safauth9");
        Session moderator = signIn("safmod9", Role.MODERATOR);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان مرجع", "Referenced Province"));
        UUID otherProvinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان دیگر", "Other Province"));
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload("جزیرهٔ مرجع", "Referring Island", reference("province", provinceId)));
        UUID portId = approvedSubject(author, moderator, "PORT",
                payload("بندر مرجع", "Referring Port", reference("province", provinceId)));
        approvedSubject(author, moderator, "ISLAND",
                payload("جزیرهٔ دیگر", "Other Island", reference("province", otherProvinceId)));
        approvedSubject(author, moderator, "ISLAND", payload("جزیرهٔ پیشین", "Formerly Here", """
                {"attribute": "province", "values": [{"subjectId": "%s"}, {"subjectId": "%s", "rank": "NORMAL"}]}"""
                .formatted(otherProvinceId, provinceId)));

        String referencedBy = "/api/subjects/" + provinceId + "/referenced-by";
        mockMvc.perform(get(referencedBy).param("attribute", "province").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(portId.toString(), islandId.toString())))
                .andExpect(jsonPath("$.data.items[*].kind").value(contains("PORT", "ISLAND")))
                .andExpect(jsonPath("$.data.items[1].name.value").value("Referring Island"));
        mockMvc.perform(get(referencedBy).param("attribute", "province").param("kind", "island"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(islandId.toString())));
        mockMvc.perform(get(referencedBy).param("attribute", "province").param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(islandId.toString())));
    }

    @Test
    void referencedByRefusesAFieldThatCannotPointHere() throws Exception {
        Session author = signIn("safauth10");
        Session moderator = signIn("safmod10", Role.MODERATOR);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload("استان خطا", "Error Province"));
        String referencedBy = "/api/subjects/" + provinceId + "/referenced-by";

        mockMvc.perform(get(referencedBy)).andExpect(status().isBadRequest());
        // Unknown, not a reference, and a reference to straits rather than provinces -- each with its own
        // reason, since the last check alone would also refuse a field that refers to nothing.
        Map<String, String> reasons = Map.of(
                "no_such_field", "There is no field with the key no_such_field.",
                "population", "The field population does not refer to other subjects.",
                "located_in", "The field located_in only refers to subjects of kind STRAIT.");
        for (Map.Entry<String, String> reason : reasons.entrySet()) {
            mockMvc.perform(get(referencedBy).param("attribute", reason.getKey())
                            .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field").value(contains("attribute")))
                    .andExpect(jsonPath("$.errors[*].message").value(contains(reason.getValue())));
        }
        mockMvc.perform(get(referencedBy).param("attribute", "province").param("kind", "VOLCANO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
        mockMvc.perform(get("/api/subjects/" + UUID.randomUUID() + "/referenced-by").param("attribute", "province"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }
}

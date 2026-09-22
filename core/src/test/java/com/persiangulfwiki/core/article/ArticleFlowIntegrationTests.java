package com.persiangulfwiki.core.article;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.subject.dto.CreateSubjectRequest;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 2 (article core): article -> translation -> revision. Every GET is public; every
// mutation needs an authenticated, email-verified account (any contributor, not
// hasRole('MODERATOR') -- deliberately different from /api/subjects). See
// ArticleController's class comment and SecurityConfig's articles requestMatchers entry for
// why. Structured the same way as SubjectFlowIntegrationTests: helpers first, then the new
// behavior, then regression coverage for what this change could plausibly have broken
// (GlobalExceptionHandler and SecurityConfig were both touched).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArticleFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private void registerContributor(String username, String email) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, PASSWORD);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
    }

    // EmailVerificationRequiredFilter blocks an unverified session from everything outside a
    // small allowlist, so any account that has to actually reach these endpoints is verified
    // here first.
    private User registerVerifiedContributor(String username, String email) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User seedModerator(String username, String email) throws Exception {
        User user = registerVerifiedContributor(username, email);
        userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        return user;
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        LoginRequest login = new LoginRequest(email, PASSWORD);
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    private String maskCsrfToken(String rawToken) {
        byte[] tokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[tokenBytes.length];
        new SecureRandom().nextBytes(random);
        byte[] xored = new byte[tokenBytes.length];
        for (int i = 0; i < tokenBytes.length; i++) {
            xored[i] = (byte) (random[i] ^ tokenBytes[i]);
        }
        byte[] combined = new byte[random.length + xored.length];
        System.arraycopy(random, 0, combined, 0, random.length);
        System.arraycopy(xored, 0, combined, random.length, xored.length);
        return Base64.getUrlEncoder().encodeToString(combined);
    }

    // Creates an ISLAND subject through the real moderator-gated path so article tests can
    // bind to a real subject id rather than a made-up one.
    private UUID createIslandSubject(Cookie accessCookie, Cookie csrfCookie) throws Exception {
        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.ISLAND, null, null, null, null);
        String created = mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));
    }

    private JsonNode simpleBody(String text) {
        return objectMapper.readTree("{\"text\":\"" + text + "\"}");
    }

    // Nested body -- an object with an array of objects nested inside -- to prove the
    // JsonNode <-> pre-serialized JSON-text String round trip (see ArticleRevision.body's
    // JSON-mapping decision comment) doesn't flatten or drop structure.
    private JsonNode nestedBody() {
        return objectMapper.readTree("""
                {"type":"doc","children":[
                    {"type":"paragraph","text":"Intro"},
                    {"type":"list","items":["one","two","three"]}
                ]}""");
    }

    private static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    // --- Smoke: the new behavior this phase adds ---

    // Main path: entityType is DERIVED from the bound subject's kind, never client-chosen,
    // and the created article is readable by a caller with no account at all -- proving the
    // public-read rule, not merely session reuse.
    @Test
    void contributorCreatesSubjectBoundArticleAndAnonymousReadsItBack() throws Exception {
        seedModerator("afmod1", "af-mod1@example.com");
        Cookie modAccess = loginAccessCookie("af-mod1@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        UUID subjectId = createIslandSubject(modAccess, modCsrf);

        registerVerifiedContributor("afauth1", "af-author1@example.com");
        Cookie accessCookie = loginAccessCookie("af-author1@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(subjectId, null, "fa",
                uniqueSlug("hormuz-island"), "Hormuz Island", simpleBody("An island."), "Summary.");

        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subjectId").value(subjectId.toString()))
                .andExpect(jsonPath("$.data.entityType").value("ISLAND"))
                .andReturn().getResponse().getContentAsString();

        UUID articleId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        mockMvc.perform(get("/api/articles/" + articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entityType").value("ISLAND"))
                .andExpect(jsonPath("$.data.subjectId").value(subjectId.toString()));
    }

    // The subjectId -> entityType derivation is one-way: a client supplying entityType on a
    // subject-bound article must be rejected, not silently overridden or accepted if it
    // happens to agree with the derived value.
    @Test
    void creatingSubjectBoundArticleWithExplicitEntityTypeIsRejected() throws Exception {
        seedModerator("afmod2", "af-mod2@example.com");
        Cookie modAccess = loginAccessCookie("af-mod2@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        UUID subjectId = createIslandSubject(modAccess, modCsrf);

        registerVerifiedContributor("afauth2", "af-author2@example.com");
        Cookie accessCookie = loginAccessCookie("af-author2@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(subjectId, EntityType.ISLAND, "fa",
                uniqueSlug("explicit-type"), "Title", simpleBody("body"), null);

        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTITY_TYPE_NOT_DERIVABLE"));
    }

    // A subject-less article has no kind to derive entityType from, so the client must say
    // GENERIC explicitly -- omitting it must not be silently treated as GENERIC by default.
    @Test
    void creatingSubjectLessArticleWithoutEntityTypeIsRejected() throws Exception {
        registerVerifiedContributor("afauth3", "af-author3@example.com");
        Cookie accessCookie = loginAccessCookie("af-author3@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, null, "fa",
                uniqueSlug("no-entity-type"), "Title", simpleBody("body"), null);

        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTITY_TYPE_NOT_DERIVABLE"));
    }

    @Test
    void creatingSubjectLessArticleWithNonGenericEntityTypeIsRejected() throws Exception {
        registerVerifiedContributor("afauth4", "af-author4@example.com");
        Cookie accessCookie = loginAccessCookie("af-author4@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.SPECIES, "fa",
                uniqueSlug("wrong-entity-type"), "Title", simpleBody("body"), null);

        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTITY_TYPE_NOT_DERIVABLE"));
    }

    // Atomicity: creating an article also writes its canonical translation and that
    // translation's first revision (revisionNumber = 1, DRAFT) in the same transaction. An
    // article with zero translations must never be observable -- the canonical-language GET
    // must succeed right after create, not 404.
    @Test
    void createIsAtomicThroughCanonicalTranslationAndFirstRevision() throws Exception {
        registerVerifiedContributor("afauth5", "af-author5@example.com");
        Cookie accessCookie = loginAccessCookie("af-author5@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("atomic-create"), "Title", nestedBody(), "Summary");

        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("fa"))
                // Created, but not published: currentRevisionId names the revision readers are
                // served, and only a moderator's approval may point it at one. A brand-new
                // article has nothing approved, so it is null -- the draft below is reachable
                // through the revision history instead.
                .andExpect(jsonPath("$.data.currentRevisionId").value(nullValue()));

        UUID revisionId = firstRevisionId(articleId);

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionNumber").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.body.type").value("doc"))
                .andExpect(jsonPath("$.data.body.children[1].items[2]").value("three"));
    }

    // Adding a second language creates its own first revision and is independently
    // readable; adding the same language twice must conflict rather than silently overwrite.
    @Test
    void addingSecondTranslationSucceedsAndDuplicateLanguageIsRejected() throws Exception {
        registerVerifiedContributor("afauth6", "af-author6@example.com");
        Cookie accessCookie = loginAccessCookie("af-author6@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest createArticle = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("multi-lang"), "Title fa", simpleBody("fa body"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        CreateTranslationRequest addEnglish = new CreateTranslationRequest(
                "en", uniqueSlug("multi-lang-en"), "Title en", simpleBody("en body"), null);
        mockMvc.perform(post("/api/articles/" + articleId + "/translations")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addEnglish)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.language").value("en"));

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("en"));

        CreateTranslationRequest duplicateEnglish = new CreateTranslationRequest(
                "en", uniqueSlug("multi-lang-en-2"), "Title en again", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles/" + articleId + "/translations")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateEnglish)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSLATION_LANGUAGE"));
    }

    // Slugs are unique across the whole table, not per-article or per-language: a second,
    // entirely different article cannot claim a slug already used by the first.
    @Test
    void slugIsGloballyUniqueAcrossArticles() throws Exception {
        registerVerifiedContributor("afauth7", "af-author7@example.com");
        Cookie accessCookie = loginAccessCookie("af-author7@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        String sharedSlug = uniqueSlug("shared-slug");
        CreateArticleRequest first = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                sharedSlug, "First", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        CreateArticleRequest second = new CreateArticleRequest(null, EntityType.GENERIC, "en",
                sharedSlug, "Second", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SLUG"));
    }

    // PATCH edits the DRAFT in place: same revision id, changed fields, and the nested JSON
    // body survives the JsonNode -> String -> JsonNode round trip (see ArticleRevision.body's
    // mapping-decision comment).
    @Test
    void patchEditsDraftRevisionInPlaceIncludingNestedBody() throws Exception {
        registerVerifiedContributor("afauth8", "af-author8@example.com");
        Cookie accessCookie = loginAccessCookie("af-author8@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest createArticle = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("patch-draft"), "Original title", simpleBody("original"), "orig summary");
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        UUID revisionId = firstRevisionId(articleId);

        UpdateRevisionRequest update = new UpdateRevisionRequest("Edited title", nestedBody(), "edited summary");
        mockMvc.perform(patch("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId)
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.title").value("Edited title"))
                .andExpect(jsonPath("$.data.summary").value("edited summary"))
                .andExpect(jsonPath("$.data.body.type").value("doc"))
                .andExpect(jsonPath("$.data.body.children[1].items[2]").value("three"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    // Scoped to the status move on purpose. Phase 3 has since landed, so submit now also opens
    // a moderation task -- but that half belongs to the moderation package and is covered by
    // ModerationFlowIntegrationTests. What this test still pins down is that the article-side
    // contract did not change when moderation was wired in: an author submitting a draft
    // observes DRAFT -> PENDING and nothing else about their own revision.
    @Test
    void submitMovesDraftToPendingOnly() throws Exception {
        registerVerifiedContributor("afauth9", "af-author9@example.com");
        Cookie accessCookie = loginAccessCookie("af-author9@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        UUID[] ids = createArticleAndReturnArticleAndRevisionId(
                accessCookie, csrfCookie, uniqueSlug("submit-flow"));
        UUID articleId = ids[0];
        UUID revisionId = ids[1];

        mockMvc.perform(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // Once a revision is PENDING, it is no longer editable -- PATCH must conflict, not
    // silently apply the edit to a revision that's supposedly awaiting review.
    @Test
    void patchOnPendingRevisionIsRejected() throws Exception {
        registerVerifiedContributor("afauth10", "af-author10@example.com");
        Cookie accessCookie = loginAccessCookie("af-author10@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        UUID[] ids = createArticleAndReturnArticleAndRevisionId(
                accessCookie, csrfCookie, uniqueSlug("patch-pending"));
        UUID articleId = ids[0];
        UUID revisionId = ids[1];

        mockMvc.perform(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        UpdateRevisionRequest update = new UpdateRevisionRequest("New title", simpleBody("new"), null);
        mockMvc.perform(patch("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId)
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    // Only the revision's author may edit their own draft -- a different verified
    // contributor, even with a perfectly valid session and CSRF token, must be forbidden.
    @Test
    void patchByNonAuthorContributorIsForbidden() throws Exception {
        registerVerifiedContributor("afauth11", "af-author11@example.com");
        Cookie authorAccess = loginAccessCookie("af-author11@example.com");
        Cookie authorCsrf = fetchCsrfCookie();

        UUID[] ids = createArticleAndReturnArticleAndRevisionId(
                authorAccess, authorCsrf, uniqueSlug("patch-non-author"));
        UUID articleId = ids[0];
        UUID revisionId = ids[1];

        registerVerifiedContributor("afother11", "af-other11@example.com");
        Cookie otherAccess = loginAccessCookie("af-other11@example.com");
        Cookie otherCsrf = fetchCsrfCookie();

        UpdateRevisionRequest update = new UpdateRevisionRequest("Hijacked title", simpleBody("hijack"), null);
        mockMvc.perform(patch("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId)
                        .cookie(otherAccess, otherCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(otherCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REVISION_AUTHOR"));
    }

    // Revision reads are scoped through (articleId, language), not a bare revisionId lookup:
    // a real revision id that belongs to a different article's translation must 404 under
    // this article's path rather than leak that article's content by id guessing.
    @Test
    void revisionFromAnotherArticleIsNotReadableUnderThisArticlesPath() throws Exception {
        registerVerifiedContributor("afauth12", "af-author12@example.com");
        Cookie accessCookie = loginAccessCookie("af-author12@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        UUID[] articleA = createArticleAndReturnArticleAndRevisionId(
                accessCookie, csrfCookie, uniqueSlug("scoped-a"));
        UUID[] articleB = createArticleAndReturnArticleAndRevisionId(
                accessCookie, csrfCookie, uniqueSlug("scoped-b"));
        UUID revisionOfA = articleA[1];
        UUID articleBId = articleB[0];

        mockMvc.perform(get("/api/articles/" + articleBId + "/translations/fa/revisions/" + revisionOfA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    // 404s for an unknown articleId, an unknown language on a real article, and an unknown
    // revisionId on a real translation -- each must resolve to its own distinct, stable code.
    @Test
    void unknownArticleLanguageAndRevisionAre404() throws Exception {
        registerVerifiedContributor("afauth13", "af-author13@example.com");
        Cookie accessCookie = loginAccessCookie("af-author13@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(get("/api/articles/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));

        UUID[] article = createArticleAndReturnArticleAndRevisionId(
                accessCookie, csrfCookie, uniqueSlug("unknown-lookups"));
        UUID articleId = article[0];

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/xx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSLATION_NOT_FOUND"));

        mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    // The entityType filter is parsed manually (like SubjectService.parseKind), so a bad
    // value must produce a translated 400 with a stable code, not a generic binding failure.
    @Test
    void listFilterWithInvalidEntityTypeIsRejected() throws Exception {
        mockMvc.perform(get("/api/articles").param("entityType", "SEA_MONSTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENTITY_TYPE"));
    }

    // Controller is deliberately NOT @Validated (see its class comment) -- @Min/@Max on a
    // plain @RequestParam without @Validated still resolves through
    // MethodArgumentNotValidException-equivalent Spring binding here. This mirrors
    // SubjectFlowIntegrationTests.pageSizeAboveTheMaximumIsRejected exactly: the size-above-
    // maximum case must stay a 400, not fall through to an unhandled 500.
    @Test
    void pageSizeAboveTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/api/articles").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // No account at all -> 401, not 403. CSRF is evaluated before the authorization decision
    // in the filter chain, so a request with a valid CSRF cookie/header but no access_token
    // cookie isolates "no authentication" from "no/bad CSRF" -- a bare request with neither
    // would 403 at the CSRF filter before ever reaching the authentication check (see
    // mutationWithoutCsrfTokenIsRejected below, which is that case).
    @Test
    void anonymousMutationIsUnauthorized() throws Exception {
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("anon-mutation"), "Title", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // Authenticated but unverified -> EmailVerificationRequiredFilter rejects with 403 before
    // the request ever reaches the controller.
    @Test
    void unverifiedContributorMutationIsForbidden() throws Exception {
        registerContributor("afunver1", "af-unverified1@example.com");
        Cookie accessCookie = loginAccessCookie("af-unverified1@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("unverified-mutation"), "Title", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutationWithoutCsrfTokenIsRejected() throws Exception {
        registerVerifiedContributor("afauth14", "af-author14@example.com");
        Cookie accessCookie = loginAccessCookie("af-author14@example.com");

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("no-csrf"), "Title", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // Sending the raw XSRF-TOKEN cookie value as the header, unmasked, must be rejected the
    // same as a missing header -- the filter chain requires the BREACH-masked encoding, not
    // just "some value that matches the cookie".
    @Test
    void mutationWithRawCsrfTokenIsRejected() throws Exception {
        registerVerifiedContributor("afauth15", "af-author15@example.com");
        Cookie accessCookie = loginAccessCookie("af-author15@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateArticleRequest request = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                uniqueSlug("raw-csrf"), "Title", simpleBody("body"), null);
        mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // Shared helper for tests that just need "a real article with a real DRAFT revision" and
    // don't care about the create response's own assertions.
    private UUID[] createArticleAndReturnArticleAndRevisionId(Cookie accessCookie, Cookie csrfCookie, String slug)
            throws Exception {
        CreateArticleRequest createArticle = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                slug, "Title", simpleBody("body"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        UUID revisionId = firstRevisionId(articleId);

        return new UUID[] {articleId, revisionId};
    }

    // A draft is found through the revision history, never through the translation's
    // currentRevisionId -- that pointer names published content and stays null until a
    // moderator approves a revision (Phase 3), so it is not a way to reach a draft.
    private UUID firstRevisionId(UUID articleId) throws Exception {
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(revisions, "$.data[0].id"));
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // SecurityConfig gained a new permitAll entry for GET /api/articles/** and a comment
    // explaining articles carry no role check at all, unlike subjects. This proves that
    // change didn't leak into the subject chain: subjects/sources reads are still public,
    // and POST /api/subjects is still moderator-gated -- a plain verified contributor (which
    // is now enough to write an article) still gets 403 there.
    @Test
    void subjectsAndSourcesReadsStillPublicAndSubjectCreateStillModeratorGated() throws Exception {
        mockMvc.perform(get("/api/subjects")).andExpect(status().isOk());
        mockMvc.perform(get("/api/sources")).andExpect(status().isOk());

        registerVerifiedContributor("afreg1", "af-reg1@example.com");
        Cookie accessCookie = loginAccessCookie("af-reg1@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.PORT, null, null, null, null);
        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // Nine new handlers were added to the shared GlobalExceptionHandler. This proves an
    // existing, unrelated handler's full ProblemDetail shape (code + timestamp + traceId,
    // not just the status) is still intact rather than shadowed or truncated by the new ones.
    @Test
    void errorProblemDetailShapeStillIntactAfterNewHandlers() throws Exception {
        mockMvc.perform(get("/api/subjects/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }
}

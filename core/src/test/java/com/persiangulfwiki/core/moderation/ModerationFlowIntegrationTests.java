package com.persiangulfwiki.core.moderation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 3 (editorial moderation): submit -> task opens -> claim -> decide. Structured the
// same way as ArticleFlowIntegrationTests -- helpers first, then the new behavior, then
// regression coverage for the Phase 2 behavior this change could plausibly have broken
// (ArticleRevisionService.submit now publishes an event, and applyModerationOutcome is a new
// write path into article_revisions/article_translations).
//
// Unlike /api/articles, nothing under /api/moderation is public: the GET is MODERATOR-gated
// too, because the queue exposes unpublished drafts. That asymmetry is asserted explicitly
// below rather than assumed.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ModerationFlowIntegrationTests {

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
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    @Autowired
    private ArticleRevisionRepository articleRevisionRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    // --- Account / session helpers (same dance as ArticleFlowIntegrationTests) ---

    private void registerContributor(String username, String email) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, PASSWORD);
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
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

    // The role must be granted before login: authorities are baked into the access token at
    // login time, so granting it afterwards would leave the session without hasRole('MODERATOR').
    private User seedModerator(String username, String email) throws Exception {
        User user = registerVerifiedContributor(username, email);
        userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        return user;
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        LoginRequest login = new LoginRequest(email, PASSWORD);
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
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

    // --- Article-side helpers: everything moderation acts on starts as a real submitted revision ---

    private record Draft(UUID articleId, UUID revisionId) {
    }

    private JsonNode simpleBody(String text) {
        return objectMapper.readTree("{\"text\":\"" + text + "\"}");
    }

    private static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private UUID createIslandSubject(Cookie accessCookie, Cookie csrfCookie) throws Exception {
        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.ISLAND, null, null, null, null);
        String created = mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private Draft createDraft(Cookie accessCookie, Cookie csrfCookie, String slug) throws Exception {
        CreateArticleRequest createArticle = new CreateArticleRequest(null, EntityType.GENERIC, "fa",
                slug, "Title", simpleBody("body"), null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        return new Draft(articleId, firstRevisionId(articleId, accessCookie));
    }

    // Subject-bound so a list query can filter by subjectId and see only this test's rows --
    // the articles table is shared with every other test in the suite.
    private Draft createSubjectBoundDraft(Cookie accessCookie, Cookie csrfCookie, UUID subjectId, String slug,
            String title, String summary) throws Exception {
        CreateArticleRequest createArticle = new CreateArticleRequest(subjectId, null, "fa", slug, title,
                simpleBody("body"), summary);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        return new Draft(articleId, firstRevisionId(articleId, accessCookie));
    }

    // Discovers a freshly created article's draft revision through the revision *history*,
    // not through the translation's currentRevisionId. Those are two different things and
    // must not be conflated in a test: currentRevisionId names published content, and a
    // brand-new article has none until a moderator approves something, so reading it here
    // would find null. Read as the author, since a draft is hidden from everyone else.
    private UUID firstRevisionId(UUID articleId, Cookie authorAccessCookie) throws Exception {
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(authorAccessCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
    }

    // The published pointer, which only an APPROVE may move. Null for any translation whose
    // content has never been approved. Read as the given caller: until something is approved the
    // translation itself is hidden from everyone but its authors and moderators.
    private UUID currentRevisionId(UUID articleId, Cookie readerAccessCookie) throws Exception {
        String translation = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa")
                        .cookie(readerAccessCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String raw = JsonPath.read(translation, "$.data.currentRevisionId");
        return raw == null ? null : UUID.fromString(raw);
    }

    private ResultActions submitRevision(Cookie accessCookie, Cookie csrfCookie, Draft draft) throws Exception {
        return mockMvc.perform(post("/api/articles/" + draft.articleId()
                        + "/translations/fa/revisions/" + draft.revisionId() + "/submit")
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())));
    }

    private ResultActions patchRevision(Cookie accessCookie, Cookie csrfCookie, Draft draft, String title)
            throws Exception {
        UpdateRevisionRequest update = new UpdateRevisionRequest(title, simpleBody("edited"), "edited summary");
        return mockMvc.perform(patch("/api/articles/" + draft.articleId()
                        + "/translations/fa/revisions/" + draft.revisionId())
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)));
    }

    // Reads as the given caller: a revision that is not APPROVED is visible only to its author
    // and to moderators, so reading an in-review revision anonymously would 404.
    private ResultActions readRevision(Cookie accessCookie, Draft draft) throws Exception {
        return mockMvc.perform(get("/api/articles/" + draft.articleId()
                        + "/translations/fa/revisions/" + draft.revisionId())
                .cookie(accessCookie));
    }

    // Creates an article, submits its first revision, and returns the task the submit opened.
    // Goes through the real endpoints on the way in; the task id itself is read back from the
    // repository rather than scanned out of the queue, because the queue is shared with every
    // other test in this class and paging to find one row would make each test depend on how
    // many others ran first. The queue endpoint's own behavior is asserted separately.
    private UUID submitAndOpenTask(Cookie accessCookie, Cookie csrfCookie, Draft draft) throws Exception {
        submitRevision(accessCookie, csrfCookie, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        return openTaskFor(draft.revisionId()).getId();
    }

    private ModerationTask openTaskFor(UUID revisionId) {
        return moderationTaskRepository.findByRevisionId(revisionId).orElseThrow();
    }

    // --- Moderation-side helpers ---

    private ResultActions claim(Cookie accessCookie, Cookie csrfCookie, Object taskId) throws Exception {
        return mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())));
    }

    private ResultActions decide(Cookie accessCookie, Cookie csrfCookie, Object taskId, Decision decision,
            String reason) throws Exception {
        DecideRequest request = new DecideRequest(decision, reason);
        return mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    // Pages the real queue endpoint looking for one task id. Paged rather than asking for a
    // single large page because every submitted revision anywhere in the suite adds a row to
    // this shared queue, so "it fits in the first page" is not something this test may assume.
    private boolean queueContains(Cookie moderatorCookie, String state, UUID taskId) throws Exception {
        for (int page = 0; page < 50; page++) {
            String body = mockMvc.perform(get("/api/moderation/tasks")
                            .param("state", state)
                            .param("page", String.valueOf(page))
                            .param("size", "100")
                            .cookie(moderatorCookie))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.data[*].id");
            if (ids.isEmpty()) {
                return false;
            }
            if (ids.contains(taskId.toString())) {
                return true;
            }
        }
        return false;
    }

    // --- Smoke: the headline scenario this phase exists to make possible ---

    // The whole editorial round trip on one subject-bound article: submit opens a task,
    // REQUEST_CHANGES hands it back without ending it, the author fixes and resubmits into
    // the *same* task, and the second round's APPROVE publishes the revision. Every state
    // transition is asserted on both sides -- the revision's status and the task's state are
    // separate facts and REQUEST_CHANGES is exactly where they diverge.
    @Test
    void submitOpensTaskThenRequestChangesReturnsItToQueueAndApprovePublishesRevision() throws Exception {
        User moderator = seedModerator("mfmod1", "mf-mod1@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod1@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        UUID subjectId = createIslandSubject(modAccess, modCsrf);

        registerVerifiedContributor("mfauth1", "mf-author1@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author1@example.com");
        Cookie authorCsrf = fetchCsrfCookie();

        CreateArticleRequest createArticle = new CreateArticleRequest(subjectId, null, "fa",
                uniqueSlug("hormuz"), "Hormuz Island", simpleBody("An island."), "Summary.");
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(authorAccess, authorCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(authorCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createArticle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.entityType").value("ISLAND"))
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        Draft draft = new Draft(articleId, firstRevisionId(articleId, authorAccess));

        // Nothing is published yet, and that is what makes the APPROVE assertion at the end of
        // this test meaningful rather than vacuous: the pointer starts null and only the
        // moderator's approval moves it.
        assertThat(currentRevisionId(articleId, authorAccess)).isNull();

        // Round 1: submit -> a task exists, OPEN and unclaimed, and it is visible in the
        // moderator's queue through the real endpoint.
        submitRevision(authorAccess, authorCsrf, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ModerationTask opened = openTaskFor(draft.revisionId());
        assertThat(opened.getState()).isEqualTo(ModerationTaskState.OPEN);
        assertThat(opened.getClaimedBy()).isNull();
        assertThat(opened.getClaimedAt()).isNull();
        UUID taskId = opened.getId();
        assertThat(queueContains(modAccess, "OPEN", taskId)).isTrue();

        claim(modAccess, modCsrf, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId.toString()))
                .andExpect(jsonPath("$.data.revisionId").value(draft.revisionId().toString()))
                .andExpect(jsonPath("$.data.state").value("CLAIMED"))
                .andExpect(jsonPath("$.data.claimedBy").value(moderator.getId().toString()))
                .andExpect(jsonPath("$.data.claimedAt").exists())
                .andExpect(jsonPath("$.data.decisions", hasSize(0)));

        decide(modAccess, modCsrf, taskId, Decision.REQUEST_CHANGES, "Needs a source for the population figure.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andExpect(jsonPath("$.data.claimedBy").value(nullValue()))
                .andExpect(jsonPath("$.data.claimedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.decisions", hasSize(1)))
                .andExpect(jsonPath("$.data.decisions[0].decision").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.data.decisions[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.data.decisions[0].moderatorId").value(moderator.getId().toString()))
                .andExpect(jsonPath("$.data.decisions[0].reason")
                        .value("Needs a source for the population figure."));

        readRevision(authorAccess, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"));

        // Round 2: the author edits the same revision in place and resubmits. The task must be
        // reused, not duplicated -- one revision has at most one task, ever.
        patchRevision(authorAccess, authorCsrf, draft, "Hormuz Island (revised)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(draft.revisionId().toString()))
                .andExpect(jsonPath("$.data.title").value("Hormuz Island (revised)"))
                .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"));

        submitRevision(authorAccess, authorCsrf, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ModerationTask reopened = openTaskFor(draft.revisionId());
        assertThat(reopened.getId()).isEqualTo(taskId);
        assertThat(reopened.getState()).isEqualTo(ModerationTaskState.OPEN);
        assertThat(moderationTaskRepository.findAll())
                .filteredOn(task -> task.getRevisionId().equals(draft.revisionId()))
                .hasSize(1);

        claim(modAccess, modCsrf, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLAIMED"));

        // APPROVE needs no reason, ends the task, and is the only thing in the API that
        // publishes a revision.
        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"))
                .andExpect(jsonPath("$.data.decisions", hasSize(2)))
                .andExpect(jsonPath("$.data.decisions[0].decision").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.data.decisions[1].decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.decisions[1].reason").value(nullValue()));

        readRevision(authorAccess, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(currentRevisionId(articleId, authorAccess)).isEqualTo(draft.revisionId());
    }

    // --- Negative / authorization ---

    // The GET is deliberately NOT public here, unlike /api/articles: the queue exposes
    // unpublished drafts and the editorial reasoning about them. A fully verified contributor
    // with a valid session and a valid CSRF token still gets 403 on all three endpoints.
    @Test
    void verifiedContributorWithoutModeratorRoleIsForbiddenEverywhereIncludingTheQueueRead() throws Exception {
        registerVerifiedContributor("mfplain2", "mf-plain2@example.com");
        Cookie accessCookie = loginAccessCookie("mf-plain2@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(get("/api/moderation/tasks").cookie(accessCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        claim(accessCookie, csrfCookie, UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        decide(accessCookie, csrfCookie, UUID.randomUUID(), Decision.APPROVE, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anonymousQueueReadIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/moderation/tasks"))
                .andExpect(status().isUnauthorized());
    }

    // Only OPEN is claimable, and that includes the moderator who already holds it -- a claim
    // is never silently extended.
    @Test
    void claimingAnAlreadyClaimedTaskIsConflict() throws Exception {
        registerVerifiedContributor("mfauth3", "mf-author3@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author3@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("claim-twice"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod3a", "mf-mod3a@example.com");
        Cookie firstAccess = loginAccessCookie("mf-mod3a@example.com");
        Cookie firstCsrf = fetchCsrfCookie();
        seedModerator("mfmod3b", "mf-mod3b@example.com");
        Cookie secondAccess = loginAccessCookie("mf-mod3b@example.com");
        Cookie secondCsrf = fetchCsrfCookie();

        claim(firstAccess, firstCsrf, taskId).andExpect(status().isOk());

        claim(secondAccess, secondCsrf, taskId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_CLAIMABLE"));

        claim(firstAccess, firstCsrf, taskId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_CLAIMABLE"));
    }

    // Holding the moderator role is not enough: only the moderator currently holding the task
    // may decide it. 403 rather than 409 -- the task is decidable, this caller just may not.
    @Test
    void decidingATaskClaimedByAnotherModeratorIsForbidden() throws Exception {
        registerVerifiedContributor("mfauth4", "mf-author4@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author4@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("other-claimant"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod4a", "mf-mod4a@example.com");
        Cookie holderAccess = loginAccessCookie("mf-mod4a@example.com");
        Cookie holderCsrf = fetchCsrfCookie();
        seedModerator("mfmod4b", "mf-mod4b@example.com");
        Cookie intruderAccess = loginAccessCookie("mf-mod4b@example.com");
        Cookie intruderCsrf = fetchCsrfCookie();

        claim(holderAccess, holderCsrf, taskId).andExpect(status().isOk());

        decide(intruderAccess, intruderCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TASK_CLAIMANT"));
    }

    // Nobody holds an OPEN task, so nobody may decide it -- "claim it first" is an
    // authorization answer here, not a state-machine one.
    @Test
    void decidingAnUnclaimedOpenTaskIsForbidden() throws Exception {
        registerVerifiedContributor("mfauth5", "mf-author5@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author5@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("unclaimed"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod5", "mf-mod5@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod5@example.com");
        Cookie modCsrf = fetchCsrfCookie();

        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TASK_CLAIMANT"));
    }

    // DECIDED is terminal and is checked before the claimant check, so even the moderator who
    // made the decision gets the 409 rather than a 403.
    @Test
    void decidingAnAlreadyDecidedTaskIsConflict() throws Exception {
        registerVerifiedContributor("mfauth6", "mf-author6@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author6@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("already-decided"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod6", "mf-mod6@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod6@example.com");
        Cookie modCsrf = fetchCsrfCookie();

        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());
        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));

        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_DECIDED"));
    }

    // The window REQUEST_CHANGES opens, and the single most important guard in this phase:
    // the task goes back to OPEN immediately and is claimable again, but its revision sits at
    // CHANGES_REQUESTED until the author resubmits. A moderator may pick the task back up at
    // any time and still must not be able to rule on content the author hasn't fixed yet.
    @Test
    void decidingAfterRequestChangesButBeforeTheAuthorResubmitsIsConflict() throws Exception {
        registerVerifiedContributor("mfauth7", "mf-author7@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author7@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("changes-window"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod7", "mf-mod7@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod7@example.com");
        Cookie modCsrf = fetchCsrfCookie();

        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());
        decide(modAccess, modCsrf, taskId, Decision.REQUEST_CHANGES, "Please cite a source.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"));

        // Claimable again: the task really is back in the queue, not parked in some
        // intermediate state.
        claim(modAccess, modCsrf, taskId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLAIMED"));

        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_PENDING"));

        // The refused decision left nothing behind: no decision row, and the revision is
        // still waiting on its author.
        readRevision(authorAccess, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"));
    }

    // Requiredness of `reason` depends on which decision was chosen, which no field-level
    // constraint can express -- so all three cases are proven against one task: a missing
    // reason and a blank one both fail, an approval with no reason succeeds.
    @Test
    void rejectAndRequestChangesRequireAReasonWhileApproveDoesNot() throws Exception {
        registerVerifiedContributor("mfauth8", "mf-author8@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author8@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("reason-rules"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod8", "mf-mod8@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod8@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());

        decide(modAccess, modCsrf, taskId, Decision.REJECT, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_DECISION_REASON"));

        decide(modAccess, modCsrf, taskId, Decision.REQUEST_CHANGES, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_DECISION_REASON"));

        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"))
                .andExpect(jsonPath("$.data.decisions", hasSize(1)));
    }

    @Test
    void unknownTaskIdIsNotFoundAndMalformedTaskIdIsBadRequest() throws Exception {
        seedModerator("mfmod9", "mf-mod9@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod9@example.com");
        Cookie modCsrf = fetchCsrfCookie();

        claim(modAccess, modCsrf, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODERATION_TASK_NOT_FOUND"));

        decide(modAccess, modCsrf, UUID.randomUUID(), Decision.APPROVE, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODERATION_TASK_NOT_FOUND"));

        claim(modAccess, modCsrf, "not-a-uuid")
                .andExpect(status().isBadRequest());
    }

    // The state filter is parsed by hand rather than bound as an enum (see DecideRequest's
    // comment), so both halves of that choice need proving: a bad value is a translated 400
    // with a stable code, and a valid value is accepted in any case.
    @Test
    void queueFilterIsCaseInsensitiveAndRejectsUnknownStatesAndOversizedPages() throws Exception {
        seedModerator("mfmod10", "mf-mod10@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod10@example.com");

        mockMvc.perform(get("/api/moderation/tasks").param("state", "open").cookie(modAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/moderation/tasks").param("state", "nonsense").cookie(modAccess))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODERATION_TASK_STATE"));

        mockMvc.perform(get("/api/moderation/tasks").param("size", "101").cookie(modAccess))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void claimWithoutCsrfTokenIsRejected() throws Exception {
        registerVerifiedContributor("mfauth11", "mf-author11@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author11@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("no-csrf"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod11", "mf-mod11@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod11@example.com");

        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim").cookie(modAccess))
                .andExpect(status().isForbidden());
    }

    // --- Regression coverage for the Phase 2 behavior this change could plausibly have broken ---

    // REJECT is terminal for the revision: applyModerationOutcome moved it to REJECTED, and
    // Phase 2's editability rule must still refuse both an edit and a resubmit of it. Another
    // attempt means authoring a brand new revision, not reviving this one.
    @Test
    void rejectedRevisionIsNeitherEditableNorResubmittable() throws Exception {
        registerVerifiedContributor("mfauth12", "mf-author12@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author12@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("rejected"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod12", "mf-mod12@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod12@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());
        decide(modAccess, modCsrf, taskId, Decision.REJECT, "Out of scope for this wiki.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));

        readRevision(authorAccess, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        patchRevision(authorAccess, authorCsrf, draft, "Try again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));

        submitRevision(authorAccess, authorCsrf, draft)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    @Test
    void approvedRevisionIsNoLongerEditable() throws Exception {
        registerVerifiedContributor("mfauth13", "mf-author13@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author13@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("approved"));
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, draft);

        seedModerator("mfmod13", "mf-mod13@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod13@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());
        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null).andExpect(status().isOk());

        patchRevision(authorAccess, authorCsrf, draft, "Post-approval edit")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));

        submitRevision(authorAccess, authorCsrf, draft)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    // Phase 2's draft-edit path, untouched by moderation: a DRAFT is still editable by its
    // author and still 403s for anyone else. submit() gained an event publication, so the
    // surrounding author/editability checks are exactly the code most likely to have been
    // disturbed by this phase.
    @Test
    void draftRevisionIsStillPatchableByItsAuthorAndForbiddenForOthers() throws Exception {
        registerVerifiedContributor("mfauth14", "mf-author14@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author14@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("draft-edit"));

        patchRevision(authorAccess, authorCsrf, draft, "Edited while still a draft")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(draft.revisionId().toString()))
                .andExpect(jsonPath("$.data.title").value("Edited while still a draft"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        registerVerifiedContributor("mfother14", "mf-other14@example.com");
        Cookie otherAccess = loginAccessCookie("mf-other14@example.com");
        Cookie otherCsrf = fetchCsrfCookie();

        patchRevision(otherAccess, otherCsrf, draft, "Hijacked")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REVISION_AUTHOR"));

        // No task was ever opened for an unsubmitted draft -- the event fires from submit(),
        // not from create or edit.
        assertThat(moderationTaskRepository.findByRevisionId(draft.revisionId())).isEmpty();
    }

    // The new MODERATOR-only path must not have tightened the article chain: article reads
    // are still reachable with no account at all, which is the rule /api/moderation
    // deliberately does not follow. What an anonymous caller may see is decided by the read
    // rules, not the chain: nothing unapproved -- an article with nothing approved yet, or an
    // unapproved revision -- which comes back as 404, never 401/403, while its author reads it.
    @Test
    void articleReadsAreStillPublicAfterTheModerationRoleGate() throws Exception {
        registerVerifiedContributor("mfauth15", "mf-author15@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author15@example.com");
        Cookie authorCsrf = fetchCsrfCookie();
        Draft draft = createDraft(authorAccess, authorCsrf, uniqueSlug("public-read"));

        mockMvc.perform(get("/api/articles").param("language", "fa")).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + draft.articleId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        mockMvc.perform(get("/api/articles/" + draft.articleId()).cookie(authorAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(draft.articleId().toString()));
        readRevision(authorAccess, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/articles/" + draft.articleId()
                        + "/translations/fa/revisions/" + draft.revisionId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    // --- Per-language article list: only approved content, and only the latest approval ---

    // The list is public, so it must only ever show what a moderator approved: an article whose
    // fa translation is still a draft is left out entirely, an article with no en translation is
    // absent from the en list, and once a translation has several revisions the row carries the
    // most recently approved one -- never an older approval, never a newer unreviewed draft.
    @Test
    void articleListPerLanguageShowsOnlyTheLatestApprovedRevisionOfEachTranslation() throws Exception {
        seedModerator("mfmod16", "mf-mod16@example.com");
        Cookie modAccess = loginAccessCookie("mf-mod16@example.com");
        Cookie modCsrf = fetchCsrfCookie();
        UUID subjectId = createIslandSubject(modAccess, modCsrf);

        User author = registerVerifiedContributor("mfauth16", "mf-author16@example.com");
        Cookie authorAccess = loginAccessCookie("mf-author16@example.com");
        Cookie authorCsrf = fetchCsrfCookie();

        String approvedSlug = uniqueSlug("list-approved");
        Draft approved = createSubjectBoundDraft(authorAccess, authorCsrf, subjectId, approvedSlug,
                "First approved title", "First approved summary");
        UUID taskId = submitAndOpenTask(authorAccess, authorCsrf, approved);
        claim(modAccess, modCsrf, taskId).andExpect(status().isOk());
        decide(modAccess, modCsrf, taskId, Decision.APPROVE, null).andExpect(status().isOk());

        createSubjectBoundDraft(authorAccess, authorCsrf, subjectId, uniqueSlug("list-draft"),
                "Unreviewed title", "Unreviewed summary");

        mockMvc.perform(get("/api/articles").param("language", "fa").param("subjectId", subjectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(approved.articleId().toString()))
                .andExpect(jsonPath("$.data[0].subjectId").value(subjectId.toString()))
                .andExpect(jsonPath("$.data[0].entityType").value("ISLAND"))
                .andExpect(jsonPath("$.data[0].language").value("fa"))
                .andExpect(jsonPath("$.data[0].slug").value(approvedSlug))
                .andExpect(jsonPath("$.data[0].title").value("First approved title"))
                .andExpect(jsonPath("$.data[0].summary").value("First approved summary"))
                .andExpect(jsonPath("$.data[0].currentRevisionId").value(approved.revisionId().toString()));

        mockMvc.perform(get("/api/articles").param("language", "en").param("subjectId", subjectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        // No endpoint creates a follow-up revision on an already-approved translation yet, so
        // the second and third revisions are inserted directly; the second is then published
        // through applyModerationOutcome, the same writer a moderator's APPROVE goes through.
        UUID translationId = articleTranslationRepository
                .findByArticleIdAndLanguage(approved.articleId(), "fa").orElseThrow().getId();
        ArticleRevision secondApproved = articleRevisionRepository.save(ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(2)
                .parentRevisionId(approved.revisionId())
                .title("Second approved title")
                .body("{\"text\":\"second\"}")
                .summary("Second approved summary")
                .status(RevisionStatus.PENDING)
                .authorId(author.getId())
                .build());
        articleRevisionService.applyModerationOutcome(secondApproved.getId(), RevisionStatus.APPROVED);
        articleRevisionRepository.save(ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(3)
                .parentRevisionId(secondApproved.getId())
                .title("Newer draft title")
                .body("{\"text\":\"third\"}")
                .summary("Newer draft summary")
                .status(RevisionStatus.DRAFT)
                .authorId(author.getId())
                .build());

        mockMvc.perform(get("/api/articles").param("language", "fa").param("subjectId", subjectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(approved.articleId().toString()))
                .andExpect(jsonPath("$.data[0].title").value("Second approved title"))
                .andExpect(jsonPath("$.data[0].summary").value("Second approved summary"))
                .andExpect(jsonPath("$.data[0].currentRevisionId").value(secondApproved.getId().toString()));

        // Adjacent behavior: the article-level read is unchanged by the list's new shape.
        mockMvc.perform(get("/api/articles/" + approved.articleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(approved.articleId().toString()))
                .andExpect(jsonPath("$.data.entityType").value("ISLAND"))
                .andExpect(jsonPath("$.data.title").doesNotExist());
    }
}

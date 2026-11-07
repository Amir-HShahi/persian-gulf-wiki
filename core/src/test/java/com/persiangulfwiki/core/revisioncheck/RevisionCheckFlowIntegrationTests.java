package com.persiangulfwiki.core.revisioncheck;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.jobqueue.JobQueueTestSupport;
import com.persiangulfwiki.core.jobqueue.JobQueueTestSupport.QueuedJob;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;
import com.persiangulfwiki.core.revisioncheck.service.RevisionCheckService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The advisory revision checks end to end, against real Postgres: a real submit queues the
// normalize and links jobs in the submit's own transaction, the test plays the worker by
// finishing those jobs rows (JobQueueTestSupport), and the real result poll copies the findings
// onto revision_checks, read back through the author's route and the moderator's task detail.
//
// The scheduled poll runs every second here too, beside the direct poll() calls; applying is
// serialized by the check's row lock, so the tests assert on the final state.
//
// Structured like the other flow tests: helpers, then the headline flow (smoke), then resubmits
// and late results, then who may read the checks, then regression coverage for the article and
// moderation routes this feature hangs off.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RevisionCheckFlowIntegrationTests {

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
    private RevisionCheckRepository revisionCheckRepository;

    @Autowired
    private RevisionCheckPollJob revisionCheckPollJob;

    @Autowired
    private RevisionCheckService revisionCheckService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- Account / article helpers ---

    private record Account(UUID userId, Cookie accessCookie) {
    }

    private record Draft(UUID articleId, UUID revisionId) {

        String checksUrl() {
            return "/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/checks";
        }
    }

    private Account verifiedAccount(boolean isModerator) throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "checks-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("chk_" + slug, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        // Before login: roles are baked into the access token.
        if (isModerator) {
            userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        }
        return new Account(user.getId(), login(email));
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Draft createDraft(Account author) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(null, "fa", "checks-" + UUID.randomUUID(),
                "جزيره کیش", objectMapper.readTree("""
                        {"text": "این جزیره دیده می شود", "href": "https://example.org/report"}
                        """), "خلاصه", null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(author.accessCookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Draft(articleId, UUID.fromString(JsonPath.read(revisions, "$.data[0].id")));
    }

    private ResultActions submit(Account author, Draft draft) throws Exception {
        return mockMvc.perform(post("/api/articles/" + draft.articleId() + "/translations/fa/revisions/"
                        + draft.revisionId() + "/submit")
                .cookie(author.accessCookie())
                .with(xsrf()));
    }

    private void edit(Account author, Draft draft) throws Exception {
        UpdateRevisionRequest update = new UpdateRevisionRequest("جزیره کیش", objectMapper.readTree(
                "{\"text\": \"این جزیره دیده می‌شود\"}"), "خلاصه", null);
        mockMvc.perform(patch("/api/articles/" + draft.articleId() + "/translations/fa/revisions/"
                        + draft.revisionId())
                        .cookie(author.accessCookie())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());
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
                .andExpect(status().isOk());
    }

    private ResultActions readChecks(Draft draft, Account caller) throws Exception {
        return mockMvc.perform(get(draft.checksUrl()).cookie(caller.accessCookie()));
    }

    // --- Worker-side helpers ---

    private QueuedJob job(List<QueuedJob> jobs, String type) {
        return jobs.stream().filter(job -> job.type().equals(type)).findFirst().orElseThrow();
    }

    private void finishNormalize(long jobId, UUID revisionId) throws Exception {
        Map<String, Object> result = JobQueueTestSupport.envelope(jobId, "normalize", "ok");
        result.put("normalize", Map.of(
                "revision_id", revisionId.toString(),
                "locale", "fa",
                "count", 2,
                "findings", List.of(
                        Map.of("rule", "arabic_yeh", "line", 1, "column", 5, "offset", 8,
                                "found", "ي", "suggest", "ی", "context", "جزيره کیش"),
                        Map.of("rule", "detached_prefix", "line", 3, "column", 16, "offset", 45,
                                "found", "می شود", "suggest", "می‌شود", "context", "این جزیره دیده می شود"))));
        JobQueueTestSupport.finish(jdbcTemplate, jobId, QueueJob.STATUS_DONE, objectMapper.writeValueAsString(result),
                null);
    }

    private void finishLinks(long jobId, UUID revisionId) throws Exception {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("url", "https://example.org/report");
        broken.put("status", "broken");
        broken.put("http_status", 404);
        broken.put("archive_url", "https://web.archive.org/web/2019/https://example.org/report");
        Map<String, Object> result = JobQueueTestSupport.envelope(jobId, "links", "ok");
        result.put("links", Map.of(
                "revision_id", revisionId.toString(),
                "count", 4,
                "broken", 1,
                "links_truncated", 0,
                "links", List.of(
                        broken,
                        Map.of("url", "http://old.example.org/", "status", "redirected", "http_status", 301,
                                "final_url", "https://new.example.org/"),
                        Map.of("url", "https://example.org/members", "status", "restricted", "http_status", 403),
                        // Dropped: shown as a link, so only http(s) is ever stored.
                        Map.of("url", "javascript:alert(1)", "status", "broken"))));
        JobQueueTestSupport.finish(jdbcTemplate, jobId, QueueJob.STATUS_DONE, objectMapper.writeValueAsString(result),
                null);
    }

    private RevisionCheck check(UUID revisionId, RevisionCheckType type) {
        return revisionCheckRepository.findByRevisionIdAndType(revisionId, type).orElseThrow();
    }

    // --- Smoke: the headline flow ---

    @Test
    void submitQueuesBothChecksAndTheWorkersFindingsReachTheAuthorAndTheModerator() throws Exception {
        Account author = verifiedAccount(false);
        Draft draft = createDraft(author);

        // Never submitted: no checks yet, and nothing queued.
        readChecks(draft, author)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionId").value(draft.revisionId().toString()))
                .andExpect(jsonPath("$.data.textCheck").value(nullValue()))
                .andExpect(jsonPath("$.data.linkCheck").value(nullValue()));
        assertThat(JobQueueTestSupport.jobsForRevision(jdbcTemplate, draft.revisionId())).isEmpty();

        submit(author, draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));

        // One pending job per check, each row waiting on its own job.
        List<QueuedJob> jobs = JobQueueTestSupport.jobsForRevision(jdbcTemplate, draft.revisionId());
        assertThat(jobs).extracting(QueuedJob::type).containsExactlyInAnyOrder("normalize", "links");
        assertThat(jobs).extracting(QueuedJob::status).containsOnly("pending");
        QueuedJob normalize = job(jobs, "normalize");
        QueuedJob links = job(jobs, "links");
        assertThat(check(draft.revisionId(), RevisionCheckType.NORMALIZE).getJobId()).isEqualTo(normalize.id());
        assertThat(check(draft.revisionId(), RevisionCheckType.LINKS).getJobId()).isEqualTo(links.id());
        readChecks(draft, author)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textCheck.status").value("PENDING"))
                .andExpect(jsonPath("$.data.textCheck.findings", hasSize(0)))
                .andExpect(jsonPath("$.data.textCheck.checkedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.linkCheck.status").value("PENDING"))
                .andExpect(jsonPath("$.data.linkCheck.links", hasSize(0)));
        // Regression: the same submit still opens the revision's moderation task.
        ModerationTask task = moderationTaskRepository.findByRevisionId(draft.revisionId()).orElseThrow();
        assertThat(task.getState()).isEqualTo(ModerationTaskState.OPEN);

        finishNormalize(normalize.id(), draft.revisionId());
        finishLinks(links.id(), draft.revisionId());
        revisionCheckPollJob.poll();

        readChecks(draft, author)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textCheck.status").value("DONE"))
                .andExpect(jsonPath("$.data.textCheck.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.data.textCheck.findingCount").value(2))
                .andExpect(jsonPath("$.data.textCheck.omittedCount").value(0))
                .andExpect(jsonPath("$.data.textCheck.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.textCheck.findings", hasSize(2)))
                .andExpect(jsonPath("$.data.textCheck.findings[0].rule").value("arabic_yeh"))
                .andExpect(jsonPath("$.data.textCheck.findings[0].found").value("ي"))
                .andExpect(jsonPath("$.data.textCheck.findings[0].suggest").value("ی"))
                .andExpect(jsonPath("$.data.textCheck.findings[1].suggest").value("می‌شود"))
                .andExpect(jsonPath("$.data.textCheck.findings[1].line").value(3))
                .andExpect(jsonPath("$.data.linkCheck.status").value("DONE"))
                .andExpect(jsonPath("$.data.linkCheck.checkedCount").value(4))
                .andExpect(jsonPath("$.data.linkCheck.brokenCount").value(1))
                .andExpect(jsonPath("$.data.linkCheck.uncheckedCount").value(0))
                .andExpect(jsonPath("$.data.linkCheck.links", hasSize(3)))
                .andExpect(jsonPath("$.data.linkCheck.links[0].url").value("https://example.org/report"))
                .andExpect(jsonPath("$.data.linkCheck.links[0].isBroken").value(true))
                .andExpect(jsonPath("$.data.linkCheck.links[0].httpStatus").value(404))
                .andExpect(jsonPath("$.data.linkCheck.links[0].archiveUrl")
                        .value("https://web.archive.org/web/2019/https://example.org/report"))
                .andExpect(jsonPath("$.data.linkCheck.links[1].finalUrl").value("https://new.example.org/"))
                .andExpect(jsonPath("$.data.linkCheck.links[1].isBroken").value(false))
                .andExpect(jsonPath("$.data.linkCheck.links[2].status").value("restricted"))
                .andExpect(jsonPath("$.data.linkCheck.links[2].isBroken").value(false));

        // The moderator gets the same checks with the revision's task.
        Account moderator = verifiedAccount(true);
        mockMvc.perform(get("/api/moderation/tasks/" + task.getId()).cookie(moderator.accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.id").value(task.getId().toString()))
                .andExpect(jsonPath("$.data.mediaReview").value(nullValue()))
                .andExpect(jsonPath("$.data.subjectReview").value(nullValue()))
                .andExpect(jsonPath("$.data.revisionChecks.revisionId").value(draft.revisionId().toString()))
                .andExpect(jsonPath("$.data.revisionChecks.textCheck.status").value("DONE"))
                .andExpect(jsonPath("$.data.revisionChecks.textCheck.findings", hasSize(2)))
                .andExpect(jsonPath("$.data.revisionChecks.linkCheck.brokenCount").value(1));
        readChecks(draft, moderator).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkCheck.status").value("DONE"));

        // Advisory only: a decision does not wait for, or consult, the checks.
        claimAndDecide(moderator, task.getId(), Decision.APPROVE, null);
        readChecks(draft, author).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textCheck.status").value("DONE"));
    }

    // --- Resubmits and late results ---

    // Every submit runs both checks again on the changed text, replacing the previous findings,
    // and a result for the earlier submit's job -- which can still finish late -- is never applied.
    @Test
    void resubmitAfterRequestChangesQueuesNewJobsReplacesTheFindingsAndIgnoresTheOldJobs() throws Exception {
        Account author = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        Draft draft = createDraft(author);
        submit(author, draft).andExpect(status().isOk());
        List<QueuedJob> firstJobs = JobQueueTestSupport.jobsForRevision(jdbcTemplate, draft.revisionId());
        QueuedJob oldNormalize = job(firstJobs, "normalize");
        QueuedJob oldLinks = job(firstJobs, "links");
        finishNormalize(oldNormalize.id(), draft.revisionId());
        revisionCheckPollJob.poll();
        assertThat(check(draft.revisionId(), RevisionCheckType.NORMALIZE).getStatus())
                .isEqualTo(RevisionCheckStatus.DONE);

        UUID taskId = moderationTaskRepository.findByRevisionId(draft.revisionId()).orElseThrow().getId();
        claimAndDecide(moderator, taskId, Decision.REQUEST_CHANGES, "Please fix the spelling.");
        edit(author, draft);
        submit(author, draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));

        // Two new jobs; both checks back to PENDING on them, the old findings gone.
        List<QueuedJob> allJobs = JobQueueTestSupport.jobsForRevision(jdbcTemplate, draft.revisionId());
        assertThat(allJobs).hasSize(4);
        List<QueuedJob> newJobs = allJobs.stream()
                .filter(job -> job.id() != oldNormalize.id() && job.id() != oldLinks.id()).toList();
        QueuedJob newNormalize = job(newJobs, "normalize");
        QueuedJob newLinks = job(newJobs, "links");
        RevisionCheck text = check(draft.revisionId(), RevisionCheckType.NORMALIZE);
        assertThat(text.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
        assertThat(text.getJobId()).isEqualTo(newNormalize.id());
        assertThat(text.getFindings()).isNull();
        assertThat(text.getTotalCount()).isNull();
        assertThat(text.getFinishedAt()).isNull();
        assertThat(check(draft.revisionId(), RevisionCheckType.LINKS).getJobId()).isEqualTo(newLinks.id());
        assertThat(revisionCheckRepository.findByRevisionId(draft.revisionId())).hasSize(2);
        // Regression: the resubmit reopened the same task rather than opening a second one.
        ModerationTask reopened = moderationTaskRepository.findByRevisionId(draft.revisionId()).orElseThrow();
        assertThat(reopened.getId()).isEqualTo(taskId);
        assertThat(reopened.getState()).isEqualTo(ModerationTaskState.OPEN);

        // The first submit's link job finishes late: never selected by the poll, and refused when
        // applied anyway.
        finishLinks(oldLinks.id(), draft.revisionId());
        revisionCheckPollJob.poll();
        RevisionCheck links = check(draft.revisionId(), RevisionCheckType.LINKS);
        revisionCheckService.applyResult(links.getId(), oldLinks.id(), QueueJob.STATUS_DONE,
                jdbcTemplate.queryForObject("select cast(result as text) from jobs where id = ?", String.class,
                        oldLinks.id()), null);
        assertThat(check(draft.revisionId(), RevisionCheckType.LINKS).getStatus())
                .isEqualTo(RevisionCheckStatus.PENDING);

        // The new jobs end otherwise: no rules for the text, and a worker that gave up.
        Map<String, Object> rejected = JobQueueTestSupport.envelope(newNormalize.id(), "normalize", "rejected");
        rejected.put("reason", Map.of("code", "unsupported_locale"));
        JobQueueTestSupport.finish(jdbcTemplate, newNormalize.id(), QueueJob.STATUS_DONE,
                objectMapper.writeValueAsString(rejected), null);
        JobQueueTestSupport.finish(jdbcTemplate, newLinks.id(), QueueJob.STATUS_FAILED, null, "time limit");
        revisionCheckPollJob.poll();

        readChecks(draft, author)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textCheck.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.textCheck.reasonCode").value("unsupported_locale"))
                .andExpect(jsonPath("$.data.textCheck.findingCount").value(nullValue()))
                .andExpect(jsonPath("$.data.textCheck.findings", hasSize(0)))
                .andExpect(jsonPath("$.data.textCheck.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.linkCheck.status").value("FAILED"))
                .andExpect(jsonPath("$.data.linkCheck.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.data.linkCheck.links", hasSize(0)));
    }

    // A finished row whose result fails validation is refused as FAILED, not stored; the other
    // check of the same submit is unaffected.
    @Test
    void aResultAboutAnotherRevisionFailsTheCheckInsteadOfStoringIt() throws Exception {
        Account author = verifiedAccount(false);
        Draft draft = createDraft(author);
        submit(author, draft).andExpect(status().isOk());
        List<QueuedJob> jobs = JobQueueTestSupport.jobsForRevision(jdbcTemplate, draft.revisionId());

        finishNormalize(job(jobs, "normalize").id(), UUID.randomUUID());
        revisionCheckPollJob.poll();

        RevisionCheck text = check(draft.revisionId(), RevisionCheckType.NORMALIZE);
        assertThat(text.getStatus()).isEqualTo(RevisionCheckStatus.FAILED);
        assertThat(text.getFindings()).isNull();
        assertThat(check(draft.revisionId(), RevisionCheckType.LINKS).getStatus())
                .isEqualTo(RevisionCheckStatus.PENDING);
    }

    // --- Who may read them ---

    @Test
    void checksAreForTheAuthorAndModeratorsOnly() throws Exception {
        Account author = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        Account stranger = verifiedAccount(false);
        Draft draft = createDraft(author);
        submit(author, draft).andExpect(status().isOk());

        // A GET under the public article reads, but signed-in only: anonymous is a 401, not a 404
        // decided as nobody.
        mockMvc.perform(get(draft.checksUrl()))
                .andExpect(status().isUnauthorized());
        // An unapproved revision is invisible to a stranger, so its checks are a 404.
        readChecks(draft, stranger)
                .andExpect(status().isNotFound());
        readChecks(draft, author).andExpect(status().isOk());
        readChecks(draft, moderator).andExpect(status().isOk());

        // Once approved, the revision itself is public; its checks still are not.
        claimAndDecide(moderator, moderationTaskRepository.findByRevisionId(draft.revisionId()).orElseThrow().getId(),
                Decision.APPROVE, null);
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/fa/revisions/"
                        + draft.revisionId()))
                .andExpect(status().isOk());
        readChecks(draft, stranger)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REVISION_AUTHOR"));
        mockMvc.perform(get(draft.checksUrl())).andExpect(status().isUnauthorized());
        readChecks(draft, author).andExpect(status().isOk());
        readChecks(draft, moderator).andExpect(status().isOk());

        // Scoped through the URL like every other revision route.
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/en/revisions/"
                        + draft.revisionId() + "/checks").cookie(author.accessCookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + UUID.randomUUID() + "/translations/fa/revisions/"
                        + draft.revisionId() + "/checks").cookie(author.accessCookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/fa/revisions/"
                        + UUID.randomUUID() + "/checks").cookie(moderator.accessCookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/fa/revisions/not-a-uuid/checks")
                        .cookie(author.accessCookie()))
                .andExpect(status().isBadRequest());
    }

    // The two filters stop downgrading an unverified session to anonymous here, so it is told
    // why rather than shown a 401.
    @Test
    void anUnverifiedAuthorIsRefusedWithEmailNotVerified() throws Exception {
        Account author = verifiedAccount(false);
        Draft draft = createDraft(author);
        User user = userRepository.findById(author.userId()).orElseThrow();
        user.setEmailVerified(false);
        userRepository.save(user);
        // Verification is read into the access token at login.
        Account unverified = new Account(author.userId(), login(user.getEmail()));

        readChecks(draft, unverified)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    // --- Regression: the routes this feature sits beside ---

    // The checks matcher is carved out of the anonymous article reads by exact shape; the reads
    // around it must stay anonymous, and the other carve-out must stay signed-in.
    @Test
    void adjacentArticleReadsStayAnonymousAndTheSlugCheckStaysSignedIn() throws Exception {
        Account author = verifiedAccount(false);
        Account moderator = verifiedAccount(true);
        Draft draft = createDraft(author);
        submit(author, draft).andExpect(status().isOk());
        claimAndDecide(moderator, moderationTaskRepository.findByRevisionId(draft.revisionId()).orElseThrow().getId(),
                Decision.APPROVE, null);

        mockMvc.perform(get("/api/articles").param("language", "fa")).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + draft.articleId())).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/fa")).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + draft.articleId() + "/translations/fa/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(draft.revisionId().toString()));
        mockMvc.perform(get("/api/articles/slug-availability").param("slug", "anything"))
                .andExpect(status().isUnauthorized());
    }
}

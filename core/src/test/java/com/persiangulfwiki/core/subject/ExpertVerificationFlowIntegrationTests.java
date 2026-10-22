package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.subject.entity.ExpertVerification;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 4: expert reviewers mark a published subject revision as correct, after publishing and
// without blocking it, and the mark goes stale once a newer revision is approved. Helpers first,
// then the new behavior, then regression coverage for the revision and moderation paths it sits on.
//
// The database is shared with every other integration test class, so queue assertions look up
// this test's own subjects rather than counting the whole queue.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExpertVerificationFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String QUEUE = "/api/expert-reviewer/verification-queue?size=100";

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
    private SubjectLiveRevisionRepository subjectLiveRevisionRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ExpertVerificationRepository expertVerificationRepository;

    // --- Helpers ---

    private record Session(UUID userId, Cookie access, Cookie csrf) {
    }

    // expertise is null for a global role, and may be null for EXPERT_REVIEWER too, which is what
    // an admin grant without one produces.
    private record Grant(Role role, @Nullable SubjectKind expertise) {
    }

    private Session signIn(String name, Grant... grants) throws Exception {
        String email = name + "@evf.example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(name, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        for (Grant grant : grants) {
            userRoleRepository.save(UserRole.builder().user(user).role(grant.role()).expertise(grant.expertise()).build());
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

    private Session contributor(String name) throws Exception {
        return signIn(name);
    }

    private Session moderator(String name) throws Exception {
        return signIn(name, new Grant(Role.MODERATOR, null));
    }

    private Session expert(String name, SubjectKind... expertise) throws Exception {
        return signIn(name, Arrays.stream(expertise)
                .map(kind -> new Grant(Role.EXPERT_REVIEWER, kind))
                .toArray(Grant[]::new));
    }

    private Session expertWithoutExpertise(String name) throws Exception {
        return signIn(name, new Grant(Role.EXPERT_REVIEWER, null));
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

    private ResultActions read(String path, Session session) throws Exception {
        return mockMvc.perform(get(path).cookie(session.access()));
    }

    private ResultActions verify(Session expert, UUID revisionId) throws Exception {
        return send(post("/api/subject-revisions/" + revisionId + "/verifications"), expert, null);
    }

    private UUID propose(Session author, String kind, String faName) throws Exception {
        String body = send(post("/api/subject-revisions"), author, """
                {"kind": "%s", "payload": {"names": {"fa": "%s"}}}""".formatted(kind, faName))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private UUID startEdit(Session author, UUID subjectId) throws Exception {
        String body = send(post("/api/subjects/" + subjectId + "/revisions"), author, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    // Submits and approves the revision, which makes it live.
    private void publish(Session author, Session moderator, UUID revisionId) throws Exception {
        send(post("/api/subject-revisions/" + revisionId + "/submit"), author, null).andExpect(status().isOk());
        UUID taskId = moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow().getId();
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null)))
                .andExpect(status().isOk());
    }

    // Proposes and publishes a subject; returns its first (live) revision's id.
    private UUID publishedProposal(Session author, Session moderator, String kind, String faName) throws Exception {
        UUID revisionId = propose(author, kind, faName);
        publish(author, moderator, revisionId);
        return revisionId;
    }

    private UUID subjectOf(UUID revisionId) {
        return subjectRevisionRepository.findById(revisionId).orElseThrow().getSubjectId();
    }

    // The subject's entry in the caller's queue, or null when it is not listed.
    private @Nullable Map<String, Object> queueEntry(Session expert, UUID subjectId) throws Exception {
        String body = read(QUEUE, expert).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> entries = JsonPath.read(body, "$.data.items[?(@.subjectId == '%s')]".formatted(subjectId));
        return entries.isEmpty() ? null : entries.getFirst();
    }

    private List<String> queuedSubjectIds(Session expert) throws Exception {
        String body = read(QUEUE, expert).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.items[*].subjectId");
    }

    // --- Smoke: the new behavior this phase adds ---

    // An unverified published subject is queued for the experts of its kind, and verifying its
    // live revision records the mark and takes it out of the queue.
    @Test
    void expertVerifiesTheLiveRevisionAndTheSubjectLeavesTheQueue() throws Exception {
        Session author = contributor("evfauth1");
        Session moderator = moderator("evfmod1");
        Session expert = expert("evfexp1", SubjectKind.ISLAND);
        UUID revisionId = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ کیش");
        UUID subjectId = subjectOf(revisionId);

        Map<String, Object> queued = queueEntry(expert, subjectId);
        assertThat(queued).isNotNull();
        assertThat(queued).containsEntry("kind", "ISLAND")
                .containsEntry("revisionId", revisionId.toString())
                .containsEntry("revisionNumber", 1)
                .containsKey("approvedAt");
        assertThat(JsonPath.<String>read(queued, "$.names.fa")).isEqualTo("جزیرهٔ کیش");
        assertThat(JsonPath.<String>read(queued, "$.verification.state")).isEqualTo("NONE");
        assertThat(JsonPath.<Object>read(queued, "$.verification.verifiedRevisionNumber")).isNull();

        verify(expert, revisionId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subjectRevisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.subjectId").value(subjectId.toString()))
                .andExpect(jsonPath("$.data.revisionNumber").value(1))
                .andExpect(jsonPath("$.data.expertId").value(expert.userId().toString()))
                .andExpect(jsonPath("$.data.verifiedAt").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());

        List<ExpertVerification> marks = expertVerificationRepository.findAll().stream()
                .filter(mark -> mark.getSubjectRevisionId().equals(revisionId))
                .toList();
        assertThat(marks).singleElement().satisfies(mark -> {
            assertThat(mark.getExpertId()).isEqualTo(expert.userId());
            assertThat(mark.getDevMarker()).isNull();
        });
        assertThat(queueEntry(expert, subjectId)).isNull();
    }

    // A mark belongs to its revision: approving a newer one makes the subject STALE, naming the
    // revision that was verified, until an expert verifies the new live revision too.
    @Test
    void approvingANewerRevisionMakesTheSubjectStaleUntilItIsVerifiedAgain() throws Exception {
        Session author = contributor("evfauth2");
        Session moderator = moderator("evfmod2");
        Session expert = expert("evfexp2", SubjectKind.ISLAND);
        UUID first = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ هندورابی");
        UUID subjectId = subjectOf(first);
        verify(expert, first).andExpect(status().isCreated());

        UUID second = startEdit(author, subjectId);
        publish(author, moderator, second);
        assertThat(subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId()).isEqualTo(second);

        Map<String, Object> stale = queueEntry(expert, subjectId);
        assertThat(stale).isNotNull();
        assertThat(stale).containsEntry("revisionId", second.toString()).containsEntry("revisionNumber", 2);
        assertThat(JsonPath.<String>read(stale, "$.verification.state")).isEqualTo("STALE");
        assertThat(JsonPath.<Integer>read(stale, "$.verification.verifiedRevisionNumber")).isEqualTo(1);

        verify(expert, first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_LIVE"));
        verify(expert, second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revisionNumber").value(2));
        assertThat(queueEntry(expert, subjectId)).isNull();
    }

    // Only an expert reviewer whose expertise is the subject's kind may verify -- checked against
    // the stored role, so neither a role with no expertise nor one revoked after sign-in passes.
    @Test
    void onlyAnExpertInTheSubjectsKindMayVerify() throws Exception {
        Session author = contributor("evfauth3");
        Session moderator = moderator("evfmod3");
        UUID revisionId = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ لاوان");

        verify(contributor("evfplain3"), revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verify(expert("evfport3", SubjectKind.PORT), revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_KIND_EXPERT"));
        verify(expertWithoutExpertise("evfnone3"), revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_KIND_EXPERT"));

        Session revoked = expert("evfrevoked3", SubjectKind.ISLAND);
        userRoleRepository.deleteAll(userRoleRepository.findByUserId(revoked.userId()));
        verify(revoked, revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_KIND_EXPERT"));

        assertThat(expertVerificationRepository.findAll())
                .noneMatch(mark -> mark.getSubjectRevisionId().equals(revisionId));
    }

    // The mark tells readers someone else checked the revision, so its author may not give it,
    // and their own revisions stay out of their queue -- while another expert can verify them.
    @Test
    void anExpertMayNotVerifyTheirOwnRevision() throws Exception {
        Session moderator = moderator("evfmod4");
        Session authoringExpert = expert("evfexp4", SubjectKind.ISLAND);
        Session otherExpert = expert("evfother4", SubjectKind.ISLAND);
        UUID revisionId = publishedProposal(authoringExpert, moderator, "ISLAND", "جزیرهٔ شیدور");
        UUID subjectId = subjectOf(revisionId);

        verify(authoringExpert, revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_VERIFICATION_NOT_ALLOWED"));
        assertThat(queueEntry(authoringExpert, subjectId)).isNull();

        assertThat(queueEntry(otherExpert, subjectId)).isNotNull();
        verify(otherExpert, revisionId).andExpect(status().isCreated());
    }

    // One mark per expert per revision; a second expert may add theirs. Anything not live is a
    // conflict, except an unapproved revision the caller may not read, which stays a 404.
    @Test
    void verifyingTwiceOrAnythingButTheLiveRevisionIsRefused() throws Exception {
        Session author = contributor("evfauth5");
        Session moderator = moderator("evfmod5");
        Session expert = expert("evfexp5", SubjectKind.ISLAND);
        Session moderatingExpert = signIn("evfmodexp5",
                new Grant(Role.EXPERT_REVIEWER, SubjectKind.ISLAND), new Grant(Role.MODERATOR, null));
        UUID live = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ فارور");

        verify(expert, live).andExpect(status().isCreated());
        verify(expert, live)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_ALREADY_VERIFIED"));
        verify(moderatingExpert, live).andExpect(status().isCreated());

        UUID draftEdit = startEdit(author, subjectOf(live));
        UUID proposal = propose(author, "ISLAND", "جزیرهٔ پیشنهادی");
        verify(expert, draftEdit)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));
        verify(expert, proposal).andExpect(status().isNotFound());
        verify(moderatingExpert, draftEdit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_LIVE"));
        verify(moderatingExpert, proposal)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_LIVE"));
        verify(expert, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));
    }

    // Newest approval first, only the caller's kinds, and a kind filter limited to them.
    @Test
    void queueListsTheCallersKindsNewestFirstAndChecksTheKindFilter() throws Exception {
        Session author = contributor("evfauth6");
        Session moderator = moderator("evfmod6");
        Session expert = expert("evfexp6", SubjectKind.ISLAND, SubjectKind.PORT);
        UUID older = subjectOf(publishedProposal(author, moderator, "ISLAND", "جزیرهٔ قدیمی"));
        UUID newer = subjectOf(publishedProposal(author, moderator, "PORT", "بندر جدید"));
        UUID strait = subjectOf(publishedProposal(author, moderator, "STRAIT", "تنگهٔ آزمایشی"));

        List<String> queued = queuedSubjectIds(expert);
        assertThat(queued).contains(older.toString(), newer.toString()).doesNotContain(strait.toString());
        assertThat(queued.indexOf(newer.toString())).isLessThan(queued.indexOf(older.toString()));

        read(QUEUE + "&kind=island", expert)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.subjectId == '%s')]".formatted(older)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.subjectId == '%s')]".formatted(newer)).isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.kind != 'ISLAND')]").isEmpty());
        // The total counts every page: both of this expert's queued subjects, with one per page.
        read("/api/expert-reviewer/verification-queue?size=1", expert)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(queued.size()));
        read(QUEUE + "&kind=STRAIT", expert)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_KIND_EXPERT"));
        read(QUEUE + "&kind=REEF", expert)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
        read("/api/expert-reviewer/verification-queue?size=101", expert)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        read(QUEUE, expertWithoutExpertise("evfnone6"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_KIND_EXPERT"));
        read(QUEUE, contributor("evfplain6"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // A marked revision reads exactly as before, publicly, and the next approval of its subject
    // is not held up by the mark (expert_verifications references the revision it marks).
    @Test
    void markedRevisionsStillReadAndApproveAsBefore() throws Exception {
        Session author = contributor("evfauth7");
        Session moderator = moderator("evfmod7");
        Session expert = expert("evfexp7", SubjectKind.ISLAND);
        UUID first = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ ناز");
        UUID subjectId = subjectOf(first);
        verify(expert, first).andExpect(status().isCreated());

        mockMvc.perform(get("/api/subject-revisions/" + first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        UUID second = startEdit(author, subjectId);
        publish(author, moderator, second);
        mockMvc.perform(get("/api/subjects/" + subjectId + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // GET /api/subject-revisions/** is public; the new POST under it must not have become so.
    @Test
    void verifyingNeedsAnAccountAndTheCsrfHeader() throws Exception {
        Session author = contributor("evfauth8");
        Session moderator = moderator("evfmod8");
        Session expert = expert("evfexp8", SubjectKind.ISLAND);
        UUID revisionId = publishedProposal(author, moderator, "ISLAND", "جزیرهٔ ام‌الکرم");

        mockMvc.perform(post("/api/subject-revisions/" + revisionId + "/verifications").with(xsrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/subject-revisions/" + revisionId + "/verifications")
                        .cookie(expert.access(), expert.csrf()))
                .andExpect(status().isForbidden());
        assertThat(expertVerificationRepository.findAll())
                .noneMatch(mark -> mark.getSubjectRevisionId().equals(revisionId));
    }
}

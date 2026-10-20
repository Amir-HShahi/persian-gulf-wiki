package com.persiangulfwiki.core.dev;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev profile only, like DevTestContentEndpointIntegrationTests: the subject revision fixture
// route, its sweeper, and a moderation task fixture pointed at a subject revision.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestSubjectRevisionEndpointIntegrationTests {

    private static final String NAMED = """
            {"names": {"fa": "%s"}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SubjectRevisionRepository subjectRevisionRepository;

    @Autowired
    private SubjectLiveRevisionRepository subjectLiveRevisionRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DevTestSubjectRevisionSweeper subjectRevisionSweeper;

    private ResultActions mint(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-subject-revisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String mintOk(String json) throws Exception {
        return mint(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private static UUID id(String body, String path) {
        return UUID.fromString(JsonPath.read(body, path));
    }

    private String mintApprovedProposal(String faName) throws Exception {
        return mintOk("""
                {"status": "APPROVED", "payload": %s}""".formatted(NAMED.formatted(faName)));
    }

    @Test
    void mintsAMarkedDraftProposalByDefault() throws Exception {
        String body = mint("{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.kind").value("ISLAND"))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.subjectId").doesNotExist())
                .andExpect(jsonPath("$.isLive").value(false))
                .andReturn().getResponse().getContentAsString();

        SubjectRevision minted = subjectRevisionRepository.findById(id(body, "$.revisionId")).orElseThrow();
        assertThat(minted.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
        assertThat(minted.getPayload()).contains(DevTestFixtures.MARKER + " subject");
    }

    // APPROVED goes through the real approval: the subject is created carrying the marker, its
    // names are written and the revision is live.
    @Test
    void mintsAnApprovedProposalThatCreatesAMarkedLiveSubject() throws Exception {
        String body = mintApprovedProposal("جزیرهٔ آزمایشی");
        UUID subjectId = id(body, "$.subjectId");

        assertThat(JsonPath.<Boolean>read(body, "$.isLive")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("APPROVED");
        assertThat(subjectRepository.findById(subjectId).orElseThrow().getDevMarker())
                .isEqualTo(DevTestFixtures.MARKER);
        assertThat(labelRepository.findBySubjectIdIn(List.of(subjectId))).extracting(Label::getValue)
                .containsExactly("جزیرهٔ آزمایشی");
    }

    // An edit is based on the subject's live revision; approving a second edit leaves the first
    // stale, which is how a suite reaches that state without racing two real submissions.
    @Test
    void mintsEditsOfAMintedSubjectBasedOnItsLiveRevision() throws Exception {
        String proposal = mintApprovedProposal("بنی‌فرور");
        UUID subjectId = id(proposal, "$.subjectId");
        UUID liveId = id(proposal, "$.revisionId");

        String pending = mint("""
                {"subjectId": "%s", "kind": "PORT", "status": "PENDING"}""".formatted(subjectId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ISLAND"))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.baseRevisionId").value(liveId.toString()))
                .andExpect(jsonPath("$.isLive").value(false))
                .andReturn().getResponse().getContentAsString();

        String approved = mintOk("""
                {"subjectId": "%s", "status": "APPROVED", "payload": %s}"""
                .formatted(subjectId, NAMED.formatted("بنی‌فرور بزرگ")));
        assertThat(JsonPath.<Boolean>read(approved, "$.isLive")).isTrue();
        assertThat(subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId())
                .isEqualTo(id(approved, "$.revisionId"))
                .isNotEqualTo(subjectRevisionRepository.findById(id(pending, "$.revisionId")).orElseThrow()
                        .getBaseRevisionId());
    }

    @Test
    void refusesToEditASubjectItDidNotMint() throws Exception {
        UUID handMade = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build()).getId();

        mint("""
                {"subjectId": "%s"}""".formatted(handMade))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    // A draft keeps whatever payload it is given, even one submit would refuse; only APPROVED,
    // which writes the read model, checks it.
    @Test
    void storesAnUncheckedPayloadUnlessAskedToApproveIt() throws Exception {
        String invalid = "{\"names\": {\"en\": \"no Farsi name\"}}";

        mint("{\"status\": \"DRAFT\", \"payload\": %s}".formatted(invalid)).andExpect(status().isCreated());
        mint("{\"status\": \"APPROVED\", \"payload\": %s}".formatted(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("payload.names.fa"));
    }

    @Test
    void deletesOnlyUnpublishedRevisionsItMinted() throws Exception {
        UUID draftId = id(mintOk("{}"), "$.revisionId");
        mockMvc.perform(delete("/api/dev/test-subject-revisions/{id}", draftId)).andExpect(status().isNoContent());
        assertThat(subjectRevisionRepository.existsById(draftId)).isFalse();
        mockMvc.perform(delete("/api/dev/test-subject-revisions/{id}", draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));

        UUID liveId = id(mintApprovedProposal("جزیرهٔ زنده"), "$.revisionId");
        mockMvc.perform(delete("/api/dev/test-subject-revisions/{id}", liveId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_TEST_SUBJECT_REVISION_LIVE"));

        UUID handMadeId = saveHandMadeRevision();
        mockMvc.perform(delete("/api/dev/test-subject-revisions/{id}", handMadeId))
                .andExpect(status().isNotFound());
        assertThat(subjectRevisionRepository.existsById(handMadeId)).isTrue();
    }

    @Test
    void mintsAPendingModerationTaskOnASubjectRevision() throws Exception {
        UUID revisionId = id(mintOk("{\"status\": \"PENDING\"}"), "$.revisionId");

        String task = mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectRevisionId\": \"%s\"}".formatted(revisionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectRevisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.revisionId").doesNotExist())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        UUID taskId = id(task, "$.taskId");
        assertThat(moderationTaskRepository.findById(taskId).orElseThrow().getState())
                .isEqualTo(ModerationTaskState.OPEN);

        mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectRevisionId\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));

        // Deleting the revision takes its task with it (V24 CASCADE).
        mockMvc.perform(delete("/api/dev/test-subject-revisions/{id}", revisionId)).andExpect(status().isNoContent());
        assertThat(moderationTaskRepository.existsById(taskId)).isFalse();
    }

    @Test
    void sweeperReclaimsAgedUnpublishedRevisionsButSparesLiveAndHandMadeOnes() throws Exception {
        UUID draftId = id(mintOk("{}"), "$.revisionId");
        UUID liveId = id(mintApprovedProposal("جزیرهٔ ماندگار"), "$.revisionId");
        UUID handMadeId = saveHandMadeRevision();

        subjectRevisionSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(subjectRevisionRepository.existsById(draftId)).isFalse();
        assertThat(subjectRevisionRepository.existsById(liveId)).isTrue();
        assertThat(subjectRevisionRepository.existsById(handMadeId)).isTrue();
    }

    private UUID saveHandMadeRevision() {
        UUID authorId = userRepository.findByEmail("contributor@dev.local").orElseThrow().getId();
        return subjectRevisionRepository.save(SubjectRevision.builder()
                .kind(SubjectKind.ISLAND)
                .revisionNumber(1)
                .payload(NAMED.formatted("دست‌ساز"))
                .status(SubjectRevisionStatus.DRAFT)
                .authorId(authorId)
                .build()).getId();
    }
}

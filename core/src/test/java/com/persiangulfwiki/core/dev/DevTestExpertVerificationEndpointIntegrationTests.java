package com.persiangulfwiki.core.dev;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.subject.entity.ExpertVerification;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev profile only, like DevTestSubjectRevisionEndpointIntegrationTests: the expert verification
// fixture route, built on that route's minted revisions.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestExpertVerificationEndpointIntegrationTests {

    private static final String NAMED = """
            {"names": {"fa": "%s"}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpertVerificationRepository expertVerificationRepository;

    @Autowired
    private SubjectRevisionRepository subjectRevisionRepository;

    @Autowired
    private UserRepository userRepository;

    private static UUID id(String body, String path) {
        return UUID.fromString(JsonPath.read(body, path));
    }

    private String mintRevision(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-subject-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions mintVerification(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-expert-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions mintVerificationOf(UUID revisionId) throws Exception {
        return mintVerification("{\"subjectRevisionId\": \"%s\"}".formatted(revisionId));
    }

    // A mark on the live revision, and one on a revision a later approval has replaced: the two
    // states (VERIFIED and STALE) a suite needs, each in one call.
    @Test
    void mintsMarkedVerificationsOnTheLiveAndOnAnOlderRevision() throws Exception {
        String first = mintRevision("""
                {"status": "APPROVED", "payload": %s}""".formatted(NAMED.formatted("جزیرهٔ نشان‌دار")));
        UUID subjectId = id(first, "$.subjectId");
        UUID firstId = id(first, "$.revisionId");
        UUID secondId = id(mintRevision("""
                {"subjectId": "%s", "status": "APPROVED", "payload": %s}"""
                .formatted(subjectId, NAMED.formatted("جزیرهٔ نشان‌دار دوم"))), "$.revisionId");
        UUID reviewerId = userRepository.findByEmail("reviewer@dev.local").orElseThrow().getId();

        String onLive = mintVerificationOf(secondId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectRevisionId").value(secondId.toString()))
                .andExpect(jsonPath("$.subjectId").value(subjectId.toString()))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.expertId").value(reviewerId.toString()))
                .andExpect(jsonPath("$.isOnLiveRevision").value(true))
                .andReturn().getResponse().getContentAsString();
        mintVerificationOf(firstId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.isOnLiveRevision").value(false));

        ExpertVerification minted = expertVerificationRepository.findById(id(onLive, "$.verificationId")).orElseThrow();
        assertThat(minted.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
        mintVerificationOf(secondId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_ALREADY_VERIFIED"));
    }

    // Never on a real revision, and never on one that isn't approved.
    @Test
    void refusesRevisionsItDidNotMintOrThatAreNotApproved() throws Exception {
        UUID handMade = subjectRevisionRepository.save(SubjectRevision.builder()
                .kind(SubjectKind.ISLAND)
                .revisionNumber(99)
                .payload(NAMED.formatted("دست‌ساز"))
                .status(SubjectRevisionStatus.APPROVED)
                .subjectId(id(mintRevision("""
                        {"status": "APPROVED", "payload": %s}""".formatted(NAMED.formatted("میزبان"))),
                        "$.subjectId"))
                .authorId(userRepository.findByEmail("contributor@dev.local").orElseThrow().getId())
                .build()).getId();
        UUID draft = id(mintRevision("{}"), "$.revisionId");

        mintVerificationOf(handMade)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));
        mintVerificationOf(draft).andExpect(status().isNotFound());
        mintVerificationOf(UUID.randomUUID()).andExpect(status().isNotFound());
        mintVerification("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // DELETE removes only what this route minted; a revision's deletion takes its marks with it.
    @Test
    void deletesOnlyVerificationsItMinted() throws Exception {
        String live = mintRevision("""
                {"status": "APPROVED", "payload": %s}""".formatted(NAMED.formatted("جزیرهٔ پاک‌شدنی")));
        UUID liveId = id(live, "$.revisionId");
        UUID mintedId = id(mintVerificationOf(liveId).andReturn().getResponse().getContentAsString(), "$.verificationId");
        UUID handMadeId = expertVerificationRepository.save(ExpertVerification.builder()
                .subjectRevisionId(liveId)
                .expertId(userRepository.findByEmail("moderator@dev.local").orElseThrow().getId())
                .build()).getId();

        mockMvc.perform(delete("/api/dev/test-expert-verifications/{id}", mintedId)).andExpect(status().isNoContent());
        assertThat(expertVerificationRepository.existsById(mintedId)).isFalse();
        mockMvc.perform(delete("/api/dev/test-expert-verifications/{id}", mintedId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPERT_VERIFICATION_NOT_FOUND"));
        mockMvc.perform(delete("/api/dev/test-expert-verifications/{id}", handMadeId))
                .andExpect(status().isNotFound());
        assertThat(expertVerificationRepository.existsById(handMadeId)).isTrue();

        // The cascade that stands in for a sweeper: deleting the test subject takes its revisions
        // and every mark on them.
        mockMvc.perform(delete("/api/dev/test-subjects/{id}", id(live, "$.subjectId")))
                .andExpect(status().isNoContent());
        assertThat(expertVerificationRepository.existsById(handMadeId)).isFalse();
    }

    // The fixture routes write past the services that clear the cached infoboxes, so the dev
    // profile clears them after every fixture write: a mark minted here, and a test subject deleted
    // here, both show on the very next read.
    @Test
    void fixtureWritesReachACachedInfobox() throws Exception {
        String live = mintRevision("""
                {"status": "APPROVED", "payload": %s}""".formatted(NAMED.formatted("جزیرهٔ کش‌شده")));
        UUID subjectId = id(live, "$.subjectId");

        mockMvc.perform(get("/api/subjects/{id}/infobox", subjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.verification.state").value("NONE"));
        mintVerificationOf(id(live, "$.revisionId")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/subjects/{id}/infobox", subjectId))
                .andExpect(jsonPath("$.data.revision.verification.state").value("VERIFIED"));

        mockMvc.perform(delete("/api/dev/test-subjects/{id}", subjectId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/subjects/{id}/infobox", subjectId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }
}

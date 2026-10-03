package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestExpertVerificationRequest;
import com.persiangulfwiki.core.dev.dto.DevTestExpertVerificationResponse;
import com.persiangulfwiki.core.subject.entity.ExpertVerification;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.exception.ExpertVerificationNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionAlreadyVerifiedException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotFoundException;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Marks a minted subject revision as verified, so an E2E suite reaches VERIFIED and STALE without
// an expert account and a live-pointer race: marking a revision that is no longer live is how STALE
// is reached in one call. Same two gates as DevTestSubjectController: @Profile("dev") here, and
// DevSecurityConfig's profiled chain for /api/dev/**.
//
// Writes the row directly, bypassing ExpertVerificationService, so none of its checks apply --
// not the expertise, not authorship, and not that the revision is live.
//
// No sweeper of its own, for the reason DevTestLabelController has none: a mark goes with its
// revision through ON DELETE CASCADE (V26), and this route only marks revisions the dev surface
// minted, which DevTestSubjectRevisionSweeper and DevTestSubjectSweeper already reclaim.
@RestController
@RequestMapping("/api/dev/test-expert-verifications")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Expert Verifications", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestExpertVerificationController {

    // DevUserSeeder's expert reviewer, never reclaimed by the user sweeper.
    private static final String SEEDED_EXPERT_EMAIL = "reviewer@dev.local";

    private final ExpertVerificationRepository expertVerificationRepository;
    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final UserRepository userRepository;
    private final DevTestArticleFixtures articleFixtures;

    @Operation(summary = "Mint a disposable test expert verification", description = "Marks an approved subject revision "
            + "minted by the dev fixture route as verified. Mark its subject's current revision for a VERIFIED subject, "
            + "or an older one for a STALE subject. No expertise, authorship or currency check is applied. `expertUserId` "
            + "defaults to the seeded reviewer account.")
    @ApiResponse(responseCode = "201", description = "The minted verification.")
    @ApiResponse(responseCode = "400", description = "`subjectRevisionId` is missing (see `errors`).")
    @ApiResponse(responseCode = "404", description = "`subjectRevisionId` names no revision, one this fixture route did not mint, "
            + "or one that is not approved.")
    @ApiResponse(responseCode = "409", description = "That account has already verified that revision.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO, no envelope -- see DevTestSubjectController.
    public DevTestExpertVerificationResponse mint(@Valid @RequestBody DevTestExpertVerificationRequest request) {
        SubjectRevision revision = subjectRevisionRepository.findById(request.subjectRevisionId())
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .filter(candidate -> candidate.getStatus() == SubjectRevisionStatus.APPROVED)
                .orElseThrow(SubjectRevisionNotFoundException::new);
        UUID expertId = request.expertUserId() != null ? request.expertUserId()
                : userRepository.findByEmail(SEEDED_EXPERT_EMAIL)
                        .map(User::getId)
                        .orElseGet(() -> articleFixtures.resolveAuthorUserId(null));
        if (expertVerificationRepository.existsBySubjectRevisionIdAndExpertId(revision.getId(), expertId)) {
            throw new SubjectRevisionAlreadyVerifiedException();
        }

        ExpertVerification verification = expertVerificationRepository.save(ExpertVerification.builder()
                .subjectRevisionId(revision.getId())
                .expertId(expertId)
                .devMarker(DevTestFixtures.MARKER)
                .build());
        boolean isOnLiveRevision = subjectLiveRevisionRepository.findById(revision.getSubjectId())
                .map(live -> live.getRevisionId().equals(revision.getId()))
                .orElse(false);
        return new DevTestExpertVerificationResponse(verification.getId(), revision.getId(), revision.getSubjectId(),
                revision.getRevisionNumber(), expertId, isOnLiveRevision);
    }

    @Operation(summary = "Delete a minted test expert verification", description = "Deletes a verification previously "
            + "created by this endpoint. Refuses anything else -- a verification made through the normal route is "
            + "reported as 404.")
    @ApiResponse(responseCode = "204", description = "The verification was deleted.")
    @ApiResponse(responseCode = "404", description = "No such verification, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{verificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID verificationId) {
        ExpertVerification verification = expertVerificationRepository.findById(verificationId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(ExpertVerificationNotFoundException::new);
        expertVerificationRepository.delete(verification);
    }
}

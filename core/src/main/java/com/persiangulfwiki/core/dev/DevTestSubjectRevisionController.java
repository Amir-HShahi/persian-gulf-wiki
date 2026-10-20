package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestSubjectRevisionRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSubjectRevisionResponse;
import com.persiangulfwiki.core.dev.exception.DevTestSubjectRevisionLiveException;
import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.subject.service.SubjectRevisionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

// Mints a subject revision in any status, for E2E suites that need a proposal, a pending edit, a
// stale draft or a live revision without walking each through submit and a moderator's decision.
// Gated exactly like DevTestSubjectController: @Profile("dev") here, and DevSecurityConfig's
// profiled chain for /api/dev/**.
//
// Writes the revision row directly, bypassing SubjectRevisionService's write paths, so states they
// refuse stay reachable (an unchecked payload, a stale base). APPROVED is the exception -- see
// DevTestSubjectRevisionRequest.
@RestController
@RequestMapping("/api/dev/test-subject-revisions")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Subject Revisions", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestSubjectRevisionController {

    private static final int SLUG_LENGTH = 12;

    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectRevisionService subjectRevisionService;
    private final DevTestArticleFixtures articleFixtures;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Mint a disposable test subject revision", description = "Creates a subject revision marked as "
            + "machine-minted. Every field is optional; the default is a DRAFT proposal of an ISLAND. With `subjectId`, "
            + "an edit of that test subject based on its live revision. Any status is stored as sent with the payload "
            + "unchecked, except APPROVED, which goes through the real approval: the payload is checked, the subject "
            + "(for a proposal) created, and the revision made live.")
    @ApiResponse(responseCode = "201", description = "The minted revision.")
    @ApiResponse(responseCode = "400", description = "APPROVED was asked for and the payload breaks a rule of the kind's fields "
            + "(see `errors`).")
    @ApiResponse(responseCode = "404", description = "`subjectId` names no subject, or one this fixture route did not mint.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO, no envelope -- see DevTestSubjectController.
    public DevTestSubjectRevisionResponse mint(@RequestBody(required = false) DevTestSubjectRevisionRequest request) {
        DevTestSubjectRevisionRequest safeRequest = request != null ? request
                : new DevTestSubjectRevisionRequest(null, null, null, null, null, null);
        SubjectRevisionStatus status =
                safeRequest.status() != null ? safeRequest.status() : SubjectRevisionStatus.DRAFT;
        SubjectPayloadRequest payload = safeRequest.payload() != null ? safeRequest.payload()
                : new SubjectPayloadRequest(new LocalizedTextRequest(DevTestFixtures.MARKER + " subject " + randomSlug(),
                        null, null), null, List.of(), null, null);

        UUID subjectId = safeRequest.subjectId();
        SubjectKind kind = safeRequest.kind() != null ? safeRequest.kind() : SubjectKind.ISLAND;
        int revisionNumber = 1;
        UUID baseRevisionId = null;
        if (subjectId != null) {
            // Same marker check as DevTestSubjectController.delete, for the same reason in reverse:
            // a fixture must never become, or be based on, a real subject's live revision.
            Subject subject = subjectRepository.findById(subjectId)
                    .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                    .orElseThrow(SubjectNotFoundException::new);
            kind = subject.getKind();
            revisionNumber = subjectRevisionRepository.findMaxRevisionNumber(subjectId) + 1;
            baseRevisionId = subjectLiveRevisionRepository.findById(subjectId)
                    .map(SubjectLiveRevision::getRevisionId)
                    .orElse(null);
        }

        // An APPROVED fixture is stored PENDING and then approved for real, which is what writes
        // the read model; the moderation task the real flow would have is not needed for that.
        SubjectRevision revision = subjectRevisionRepository.saveAndFlush(SubjectRevision.builder()
                .subjectId(subjectId)
                .kind(kind)
                .revisionNumber(revisionNumber)
                .baseRevisionId(baseRevisionId)
                .payload(objectMapper.writeValueAsString(payload))
                .summary(safeRequest.summary())
                .status(status == SubjectRevisionStatus.APPROVED ? SubjectRevisionStatus.PENDING : status)
                .authorId(articleFixtures.resolveAuthorUserId(safeRequest.authorUserId()))
                .devMarker(DevTestFixtures.MARKER)
                .build());
        if (status == SubjectRevisionStatus.APPROVED) {
            subjectRevisionService.applyModerationOutcome(revision.getId(), SubjectRevisionStatus.APPROVED);
        }

        return new DevTestSubjectRevisionResponse(revision.getId(), revision.getSubjectId(), revision.getKind(),
                revision.getRevisionNumber(), revision.getBaseRevisionId(), revision.getStatus(),
                subjectLiveRevisionRepository.existsByRevisionId(revision.getId()));
    }

    @Operation(summary = "Delete a minted test subject revision", description = "Deletes a revision previously created by "
            + "this endpoint, together with its moderation task if it has one. Refuses anything else -- a revision made "
            + "through the normal routes is reported as 404. A revision that is its subject's live revision is refused "
            + "as 409: delete its test subject instead, which takes every revision with it.")
    @ApiResponse(responseCode = "204", description = "The revision was deleted.")
    @ApiResponse(responseCode = "404", description = "No such revision, or the id names one this endpoint did not mint.")
    @ApiResponse(responseCode = "409", description = "The revision is its subject's live revision.")
    @DeleteMapping("/{revisionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID revisionId) {
        SubjectRevision revision = subjectRevisionRepository.findById(revisionId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(SubjectRevisionNotFoundException::new);
        if (subjectLiveRevisionRepository.existsByRevisionId(revisionId)) {
            throw new DevTestSubjectRevisionLiveException();
        }
        // moderation_tasks.subject_revision_id is ON DELETE CASCADE and base_revision_id ON DELETE
        // SET NULL (V24), so neither a task nor an edit based on this one blocks the delete.
        subjectRevisionRepository.delete(revision);
    }

    private static String randomSlug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
    }
}

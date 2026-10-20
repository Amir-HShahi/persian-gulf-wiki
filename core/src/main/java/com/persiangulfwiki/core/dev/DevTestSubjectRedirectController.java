package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestSubjectRedirectRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSubjectRedirectResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectRedirect;
import com.persiangulfwiki.core.subject.exception.InvalidMergeTargetException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

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

import java.time.Instant;
import java.util.UUID;

// Marks one minted subject as merged into another, so an E2E suite reaches the merged-away state --
// GET by id answering with the target, links and filters following the redirect, restore refused --
// in one call. Same two gates as DevTestSubjectController: @Profile("dev") here, and DevSecurityConfig's
// profiled chain for /api/dev/**.
//
// Writes the redirect and the source's deleted_at directly, bypassing SubjectMergeService: nothing is
// moved and no revision is written. It keeps the one invariant the read side relies on, that redirects
// are one hop: the target must not be deleted, and redirects into the source are repointed at the
// target, as a real merge does.
//
// No sweeper of its own, for the reason DevTestExpertVerificationController has none: both ends are
// minted subjects, and subject_redirects goes with either through ON DELETE CASCADE (V29), so
// DevTestSubjectSweeper already reclaims it.
@RestController
@RequestMapping("/api/dev/test-subject-redirects")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Subjects")
public class DevTestSubjectRedirectController {

    private final SubjectRepository subjectRepository;
    private final SubjectRedirectRepository subjectRedirectRepository;

    @Operation(summary = "Mark a test subject as merged into another", description = "Both subjects must have been "
            + "minted by the dev fixture route, and the target must not be deleted. Deletes the source and redirects "
            + "it to the target, without moving anything: no facts, aliases or articles change hands and the target "
            + "gets no new revision. Marking an already merged source again points it at the new target.")
    @ApiResponse(responseCode = "201", description = "The redirect.")
    @ApiResponse(responseCode = "400", description = "`sourceId` or `targetId` is missing (see `errors`), or the target is "
            + "deleted or the source itself (an `errors` entry on `targetId`).")
    @ApiResponse(responseCode = "404", description = "Either id names no subject, or one this fixture route did not mint.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO, no envelope -- see DevTestSubjectController.
    public DevTestSubjectRedirectResponse mint(@Valid @RequestBody DevTestSubjectRedirectRequest request) {
        if (request.sourceId().equals(request.targetId())) {
            throw new InvalidMergeTargetException("cannot redirect a subject to itself",
                    "validation.subjectMerge.sameSubject");
        }
        Subject source = findMinted(request.sourceId());
        Subject target = findMinted(request.targetId());
        if (target.isDeleted()) {
            throw new InvalidMergeTargetException("redirect target " + target.getId() + " is deleted");
        }

        subjectRedirectRepository.repointTargets(source.getId(), target.getId());
        // Replaces an existing redirect of the source, if any.
        subjectRedirectRepository.findById(source.getId()).ifPresent(subjectRedirectRepository::delete);
        subjectRedirectRepository.flush();
        SubjectRedirect redirect = subjectRedirectRepository.save(SubjectRedirect.builder()
                .sourceId(source.getId())
                .targetId(target.getId())
                .build());
        if (!source.isDeleted()) {
            source.setDeletedAt(Instant.now());
            subjectRepository.save(source);
        }
        return new DevTestSubjectRedirectResponse(source.getId(), target.getId(), redirect.getMergedAt());
    }

    @Operation(summary = "Delete a minted test redirect", description = "Removes the redirect of a source this route "
            + "marked. The source stays deleted -- an ordinary deleted subject, which a moderator can restore. Refuses "
            + "a redirect between subjects the fixture route did not both mint.")
    @ApiResponse(responseCode = "204", description = "The redirect was deleted.")
    @ApiResponse(responseCode = "404", description = "No redirect from that id, or one whose ends were not both minted.")
    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID sourceId) {
        SubjectRedirect redirect = subjectRedirectRepository.findById(sourceId)
                .filter(candidate -> isMinted(candidate.getSourceId()) && isMinted(candidate.getTargetId()))
                // Same reuse of the production 404 as DevTestSubjectController.delete.
                .orElseThrow(SubjectNotFoundException::new);
        subjectRedirectRepository.delete(redirect);
    }

    private Subject findMinted(UUID subjectId) {
        return subjectRepository.findById(subjectId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(SubjectNotFoundException::new);
    }

    private boolean isMinted(UUID subjectId) {
        return subjectRepository.findById(subjectId)
                .map(subject -> DevTestFixtures.MARKER.equals(subject.getDevMarker()))
                .orElse(false);
    }
}

package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.exception.RevisionNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.dev.dto.DevTestRevisionCheckRequest;
import com.persiangulfwiki.core.dev.dto.DevTestRevisionCheckResponse;
import com.persiangulfwiki.core.dev.exception.DevTestRevisionCheckExistsException;
import com.persiangulfwiki.core.dev.exception.InvalidDevTestRevisionCheckRequestException;
import com.persiangulfwiki.core.revisioncheck.entity.LinkFinding;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckFindings;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;
import com.persiangulfwiki.core.revisioncheck.entity.TextFinding;
import com.persiangulfwiki.core.revisioncheck.exception.RevisionCheckNotFoundException;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Mints a revision's advisory check in any state -- PENDING, DONE with sample findings, REJECTED,
// FAILED -- for E2E suites that need one without a submit and a worker run. Gated like every other
// fixture controller: @Profile("dev") here, and DevSecurityConfig's profiled chain for /api/dev/**.
//
// Bypasses RevisionCheckService on purpose: DONE, REJECTED and FAILED are only reachable through
// the worker. A minted PENDING check has no job behind it, so it stays PENDING until it is deleted
// (or a real submit of the revision resets it, which also clears its marker). The real path -- a
// submit through the article routes with the pipeline worker running -- needs no fixture.
//
// Only rows carrying the dev marker are ever deleted here; a row a real submit wrote is a 404.
@RestController
@RequestMapping("/api/dev/test-revision-checks")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Revision Checks", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered "
        + "in any other profile.")
public class DevTestRevisionCheckController {

    private final RevisionCheckRepository revisionCheckRepository;
    private final ArticleRevisionRepository articleRevisionRepository;

    @Operation(summary = "Mint a disposable revision check", description = "Attaches a check marked as machine-minted to "
            + "an existing article revision, in the given state. A DONE text check carries two sample suggestions; "
            + "a DONE link check carries four links: working, moved, broken (with an archived copy) and one that "
            + "refuses robots, which must not be shown as broken. A REJECTED one carries a sample reason code.")
    @ApiResponse(responseCode = "201", description = "The created check.")
    @ApiResponse(responseCode = "400", description = "`revisionId` is missing.")
    @ApiResponse(responseCode = "404", description = "No revision with that id.")
    @ApiResponse(responseCode = "409", description = "The revision already has a check of this type.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO rather than the usual envelope, matching the other fixture controllers.
    public DevTestRevisionCheckResponse mint(@RequestBody(required = false) DevTestRevisionCheckRequest request) {
        if (request == null || request.revisionId() == null) {
            throw new InvalidDevTestRevisionCheckRequestException();
        }
        if (!articleRevisionRepository.existsById(request.revisionId())) {
            throw new RevisionNotFoundException();
        }
        RevisionCheckType type = request.type() != null ? request.type() : RevisionCheckType.NORMALIZE;
        RevisionCheckStatus status = request.status() != null ? request.status() : RevisionCheckStatus.DONE;
        if (revisionCheckRepository.findByRevisionIdAndType(request.revisionId(), type).isPresent()) {
            throw new DevTestRevisionCheckExistsException();
        }

        RevisionCheck.RevisionCheckBuilder<?, ?> check = RevisionCheck.builder()
                .revisionId(request.revisionId())
                .type(type)
                .status(status)
                .finishedAt(status == RevisionCheckStatus.PENDING ? null : Instant.now())
                .devMarker(DevTestFixtures.MARKER);
        if (status == RevisionCheckStatus.REJECTED) {
            check.reasonCode(type == RevisionCheckType.NORMALIZE ? "unsupported_locale" : "malformed_body");
        } else if (status == RevisionCheckStatus.DONE && type == RevisionCheckType.NORMALIZE) {
            List<TextFinding> findings = List.of(
                    new TextFinding("arabic_yeh", 1, 3, 4, "ي", "ی", "جزيره کیش"),
                    new TextFinding("detached_prefix", 2, 16, 45, "می شود", "می‌شود", "این جزیره دیده می شود"));
            check.totalCount(findings.size()).problemCount(findings.size()).omittedCount(0)
                    .findings(new RevisionCheckFindings(findings, List.of()));
        } else if (status == RevisionCheckStatus.DONE) {
            List<LinkFinding> links = List.of(
                    new LinkFinding("https://example.org/ok", "ok", 200, null, null, null),
                    new LinkFinding("http://old.example.org/", "redirected", 200, "https://new.example.org/", null,
                            null),
                    new LinkFinding("https://example.org/gone", "broken", 404, null,
                            "https://web.archive.org/web/2019/https://example.org/gone", null),
                    new LinkFinding("https://example.org/members", "restricted", 403, null, null, null));
            check.totalCount(links.size()).problemCount(1).omittedCount(0)
                    .findings(new RevisionCheckFindings(List.of(), links));
        }
        RevisionCheck saved = revisionCheckRepository.save(check.build());
        return new DevTestRevisionCheckResponse(saved.getId(), saved.getRevisionId(), saved.getType(),
                saved.getStatus());
    }

    @Operation(summary = "Delete a minted revision check", description = "Deletes a check previously created by this "
            + "endpoint. Refuses anything else -- a check written by a real submit is reported as 404 rather than "
            + "deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The check was deleted.")
    @ApiResponse(responseCode = "404", description = "No such check, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{checkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID checkId) {
        // The marker check is what makes accepting an arbitrary id safe -- see
        // DevTestSubjectController.delete.
        RevisionCheck check = revisionCheckRepository.findById(checkId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(RevisionCheckNotFoundException::new);
        revisionCheckRepository.delete(check);
    }
}

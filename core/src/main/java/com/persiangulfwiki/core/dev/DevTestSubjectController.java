package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestSubjectRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSubjectResponse;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.OilField;
import com.persiangulfwiki.core.subject.entity.Port;
import com.persiangulfwiki.core.subject.entity.Species;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.OilFieldRepository;
import com.persiangulfwiki.core.subject.repository.PortRepository;
import com.persiangulfwiki.core.subject.repository.SpeciesRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

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

import java.util.UUID;

// Mints a throwaway subject per call, for E2E suites that need something to hang an article
// or a measurement off without first getting a moderator account through the real create
// path.
//
// Two independent gates keep this out of production, exactly as for DevTestUserController:
// @Profile("dev") here, so the bean does not exist, and DevSecurityConfig's profiled filter
// chain, so /api/dev/** is not permitted anywhere else. Both are needed.
//
// Deliberately bypasses SubjectService. That path always writes the subtype row alongside the
// subject, which makes "a subject with no detail row" unreachable — and that is precisely the
// state a test needs to prove a read of one returns empty detail fields instead of failing.
// Writing the rows directly through the repositories is what makes such states possible.
@RestController
@RequestMapping("/api/dev/test-subjects")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Subjects", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestSubjectController {

    private final SubjectRepository subjectRepository;
    private final IslandRepository islandRepository;
    private final PortRepository portRepository;
    private final OilFieldRepository oilFieldRepository;
    private final SpeciesRepository speciesRepository;

    @Operation(summary = "Mint a disposable test subject", description = "Creates a subject marked as machine-minted. Every field of the request body is "
            + "optional; the default is an ISLAND with an empty detail row. Set `withDetailRow` "
            + "false to mint a subject with no detail row at all — a state the normal create "
            + "endpoint cannot produce.")
    @ApiResponse(responseCode = "201", description = "The created subject.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Returns the DTO bare rather than wrapped in the usual envelope, matching
    // DevTestUserController: there is no message worth translating for a machine-only caller.
    public DevTestSubjectResponse mint(@RequestBody(required = false) DevTestSubjectRequest request) {
        DevTestSubjectRequest safeRequest = request != null ? request : new DevTestSubjectRequest(null, null);
        SubjectKind kind = safeRequest.kind() != null ? safeRequest.kind() : SubjectKind.ISLAND;
        boolean withDetailRow = safeRequest.withDetailRow() == null || safeRequest.withDetailRow();

        Subject subject = subjectRepository.save(Subject.builder()
                .kind(kind)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        if (withDetailRow) {
            // Geometry columns are left null on purpose — a fixture exists to be pointed at,
            // and inventing coordinates would put meaningless points on any map built from
            // this data during development.
            switch (kind) {
                case ISLAND -> islandRepository.save(Island.builder().subject(subject).build());
                case PORT -> portRepository.save(Port.builder().subject(subject).build());
                case OIL_FIELD -> oilFieldRepository.save(OilField.builder().subject(subject).build());
                case SPECIES -> speciesRepository.save(Species.builder().subject(subject).build());
            }
        }

        return new DevTestSubjectResponse(subject.getId(), kind, withDetailRow);
    }

    @Operation(summary = "Delete a minted test subject", description = "Deletes a subject previously created by this endpoint. Refuses anything else — a "
            + "subject created through the normal endpoint is reported as 404 rather than "
            + "deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The subject was deleted.")
    @ApiResponse(responseCode = "404", description = "No such subject, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID subjectId) {
        // Deliberately narrower than "delete whatever id I am given". A teardown hook that
        // loses track of what it minted must not be able to remove a subject a human created
        // — the marker check is what makes handing an arbitrary id to this route safe.
        Subject subject = subjectRepository.findById(subjectId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                // Reuses the production exception rather than a dev-only one:
                // GlobalExceptionHandler already maps it to 404 SUBJECT_NOT_FOUND.
                .orElseThrow(SubjectNotFoundException::new);

        // The subtype and geometry tables are ON DELETE CASCADE on subjects (id) — see V15 —
        // so the detail row and any attached geometries go with it.
        subjectRepository.delete(subject);
    }
}

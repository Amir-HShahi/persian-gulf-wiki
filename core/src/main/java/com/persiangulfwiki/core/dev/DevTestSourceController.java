package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestSourceRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSourceResponse;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.exception.SourceNotFoundException;
import com.persiangulfwiki.core.source.repository.SourceRepository;

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

// Mints a throwaway citation per call. Same two gates and the same reasoning as
// DevTestSubjectController — see that class.
//
// Bypasses SourceService so a fixture can be attributed to an arbitrary account without
// authenticating as it, which is the state a provenance test needs and the authenticated
// create path cannot produce.
@RestController
@RequestMapping("/api/dev/test-sources")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Sources", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestSourceController {

    // Enough of the UUID to keep parallel runs from producing identical titles in a failing
    // test's output, short enough to stay readable there.
    private static final int SLUG_LENGTH = 12;

    private final SourceRepository sourceRepository;

    @Operation(summary = "Mint a disposable test source", description = "Creates a source marked as machine-minted. Both fields of the request body are "
            + "optional; the default is a generated title and no creating account.")
    @ApiResponse(responseCode = "201", description = "The created source.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestSourceResponse mint(@RequestBody(required = false) DevTestSourceRequest request) {
        DevTestSourceRequest safeRequest = request != null ? request : new DevTestSourceRequest(null, null);
        String slug = UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        String title = safeRequest.title() != null ? safeRequest.title() : "e2e source " + slug;

        Source source = sourceRepository.save(Source.builder()
                .title(title)
                .createdByUserId(safeRequest.createdByUserId())
                .devMarker(DevTestFixtures.MARKER)
                .build());

        return new DevTestSourceResponse(source.getId(), source.getTitle(), source.getCreatedByUserId());
    }

    @Operation(summary = "Delete a minted test source", description = "Deletes a source previously created by this endpoint. Refuses anything else — a "
            + "source created through the normal endpoint is reported as 404 rather than "
            + "deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The source was deleted.")
    @ApiResponse(responseCode = "404", description = "No such source, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID sourceId) {
        Source source = sourceRepository.findById(sourceId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(SourceNotFoundException::new);

        sourceRepository.delete(source);
    }
}

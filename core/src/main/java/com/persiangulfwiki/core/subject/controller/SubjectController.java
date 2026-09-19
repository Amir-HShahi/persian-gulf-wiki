package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.subject.dto.CreateSubjectRequest;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.service.SubjectService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Deliberately NOT annotated @Validated. Spring applies method validation to
// constrained @RequestParam/@PathVariable parameters by itself, and reports a failure as
// the request-handling error the shared error advice already turns into a 400 with a
// per-field breakdown. Adding @Validated replaces that with an AOP proxy that raises a
// plain constraint-violation error instead, which nothing handles — so `size=101` comes
// back as a 500 rather than a 400. Verified by test: pageSizeAboveTheMaximumIsRejected.
@RestController
@RequestMapping("/api/subjects")
@RequiredArgsConstructor
@Tag(name = "Subjects", description = "The things the wiki is about — islands, ports, oil fields and species. "
        + "A subject carries only its identity, its kind and the typed detail belonging to that "
        + "kind; its names are per-language and come from the labels endpoints, and its prose "
        + "comes from the articles bound to it. Reading is open to everyone; creating requires "
        + "moderator rights.")
public class SubjectController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SubjectService subjectService;
    private final MessageSource messageSource;

    @Operation(summary = "Create a subject", description = "Creates the subject and its kind-specific detail together, in a single step — "
            + "there is no separate call to fill in the detail afterwards. Only the fields "
            + "belonging to the given kind may be sent: `areaKm2` and `location` for ISLAND, "
            + "`location` for PORT, `area` for OIL_FIELD, `habitat` for SPECIES. Sending a field "
            + "that belongs to a different kind is rejected rather than ignored, so a value is "
            + "never silently dropped. Every detail field is optional — a subject whose shape or "
            + "size isn't known yet is valid. Geometries are WKT strings with longitude/latitude "
            + "coordinates in WGS84, e.g. `POINT(52.1 26.4)`.")
    @ApiResponse(responseCode = "201", description = "The created subject, including any detail supplied for its kind.")
    @ApiResponse(responseCode = "400", description = "One of: `kind` is missing or is not one of ISLAND, PORT, OIL_FIELD, SPECIES; "
            + "`areaKm2` is negative; a detail field was sent that does not belong to the given "
            + "kind; or a geometry string is not valid WKT, or is a different geometry type than "
            + "the field accepts (a point where a polygon is required, or the reverse). When the "
            + "failure is field validation, `detail` is the fixed summary \"validation failed\" and "
            + "the specific failures are in the `errors` array, one entry per field with `field` "
            + "and `message`; the other cases carry the reason in `detail` and a `code` of "
            + "SUBJECT_KIND_MISMATCH or INVALID_GEOMETRY.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but lacks moderator rights, its email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie — all "
            + "three surface as a plain 403 with no way to tell them apart from the status code "
            + "alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<SubjectResponse> create(@Valid @RequestBody CreateSubjectRequest request) {
        SubjectResponse data = subjectService.create(request);
        String message = messageSource.getMessage("success.subjectCreated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a subject by id", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "The subject, including any detail recorded for its kind.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{subjectId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectResponse> get(@PathVariable UUID subjectId) {
        SubjectResponse data = subjectService.get(subjectId);
        String message = messageSource.getMessage("success.subjectFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List subjects", description = "Open to everyone; no account is required. Optionally filtered by kind and paged.")
    @ApiResponse(responseCode = "200", description = "One page of subjects. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "`kind` is not one of ISLAND, PORT, OIL_FIELD, SPECIES (case-insensitive), or "
            + "`page`/`size` is outside its allowed range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "kind", description = "Optional filter: ISLAND, PORT, OIL_FIELD, or SPECIES (case-insensitive). Omit to return every kind.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SubjectResponse>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        List<SubjectResponse> data = subjectService.list(kind, PageRequest.of(page, size));
        String message = messageSource.getMessage("success.subjectsList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }
}

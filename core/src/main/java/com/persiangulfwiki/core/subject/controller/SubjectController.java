package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.dto.SubjectInfoboxResponse;
import com.persiangulfwiki.core.subject.dto.SubjectListResponse;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxService;
import com.persiangulfwiki.core.subject.service.SubjectListingService;
import com.persiangulfwiki.core.subject.service.SubjectService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
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
@Tag(name = "Subjects", description = "The things the wiki is about — islands, ports, oil fields, species, straits, "
        + "fishing grounds and provinces. "
        + "A subject carries only its identity, its kind and the typed detail belonging to that "
        + "kind; its names are per-language, its facts are read through its infobox, and its prose "
        + "comes from the articles bound to it. Reading is open to everyone. There is no direct "
        + "create or edit: a new subject is proposed, and an existing one changed, through a "
        + "subject revision that a moderator approves (see Subject Revisions).")
public class SubjectController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SubjectService subjectService;
    private final SubjectInfoboxService subjectInfoboxService;
    private final SubjectListingService subjectListingService;
    private final MessageSource messageSource;

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

    @Operation(summary = "Get a subject's infobox", description = "Open to everyone; no account is required. The facts "
            + "of the subject's published revision that its kind shows in an infobox, grouped and ordered as the field "
            + "vocabulary says (groups by `sort`, fields by `sort` within a group, values in the order they were "
            + "recorded). Only each field's preferred values are shown. Rows computed from other data, such as "
            + "population density from population and area, have `isDerived` true and appear only when their inputs "
            + "exist. Every label and name is in the language picked from `Accept-Language` (fa, en or ar; anything "
            + "else gets fa); a missing English or Arabic one falls back to Farsi, with `isFallback` true. Numbers are "
            + "raw: format their digits for the reader. A referenced subject carries the slug of its main article in "
            + "the reader's language, else in Farsi, or null when it has no published article. `revision` gives the "
            + "published revision's number and its expert verification state: VERIFIED, STALE (with the older "
            + "revision an expert confirmed) or NONE. A subject with no published revision yet has empty `groups` and "
            + "a null `revision`. Edits awaiting review never show here.")
    @ApiResponse(responseCode = "200", description = "The infobox.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{subjectId}/infobox")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectInfoboxResponse> getInfobox(@PathVariable UUID subjectId) {
        Locale locale = LocaleContextHolder.getLocale();
        SubjectInfoboxResponse data =
                subjectInfoboxService.get(subjectId, SupportedLocales.normalize(locale).getLanguage());
        String message = messageSource.getMessage("success.subjectInfoboxFetched", null, locale);
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List and filter subjects", description = "Open to everyone; no account is required. One page "
            + "of subjects, newest first, with the counts a filter panel is built from.\n\n"
            + "**Filters.** `kind` takes one or more kinds, comma-separated or repeated; none means every kind. Each "
            + "filterable field of the selected kinds is a query parameter `attr.{key}`: choice codes for a choice "
            + "field (`attr.iucn_status=EN,VU`), subject ids for a field referring to subjects "
            + "(`attr.province=<id>`), or `true` for a yes/no field (`attr.has_360_tour=true`). Values within one "
            + "parameter are alternatives (any of them matches); separate parameters must all match. An empty "
            + "parameter selects nothing and is ignored. Field filters need at least one `kind`, since fields belong "
            + "to kinds; the keys and codes come from `GET /api/subject-kinds/{kind}/attributes` (fields with "
            + "`isFilterable` true). Only published data is filtered on; edits awaiting review never count.\n\n"
            + "**Facets.** `facets` starts with `kind`, listing every kind, followed by one entry per filterable field "
            + "of the selected kinds (none when no kind is selected), in the vocabulary's order. Every option is "
            + "listed, even at count 0, so the panel keeps its shape. A field's counts apply every other filter but "
            + "not its own, so each count is how many subjects match that option alongside the rest of the "
            + "selection; a subject holding several options of a field counts once under each. A yes/no field has a "
            + "single option, `true`, labelled with the field's own label. Retired choices are listed with "
            + "`isDeprecated` true.\n\n"
            + "**Items.** Each carries the subject's name and its main article's slug in the reader's language "
            + "(`Accept-Language`: fa, en or ar; anything else gets fa), falling back to Farsi; a null slug means the "
            + "subject has no published article and so no page. `badges` maps the fields shown on cards to their "
            + "codes; their labels are among the facet options.")
    @ApiResponse(responseCode = "200", description = "One page of subjects, the total across all pages, and the facets. "
            + "An out-of-range page has no items but still has its total and facets.")
    @ApiResponse(responseCode = "400", description = "`kind` names a kind that does not exist (code INVALID_SUBJECT_KIND); "
            + "or `page`/`size` is out of range; or a field filter names an unknown field, a field of none of the "
            + "selected kinds, a field that cannot be filtered on, an unknown choice code, a subject that does not "
            + "exist or is of the wrong kind, or a yes/no value other than `true`; or field filters were sent "
            + "without a `kind`. All of these but the first answer VALIDATION_FAILED with an `errors` entry per "
            + "offending parameter.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "kind", description = "ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, FISHING_GROUND or PROVINCE "
            + "(case-insensitive); comma-separated or repeated. Omit for every kind.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectListResponse> list(
            @RequestParam(required = false) List<String> kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            // Every query parameter, for the `attr.{key}` filters, whose names are not known up front;
            // the operation description documents them instead.
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        Locale locale = LocaleContextHolder.getLocale();
        SubjectListResponse data = subjectListingService.list(kind, parameters,
                SupportedLocales.normalize(locale).getLanguage(), PageRequest.of(page, size));
        String message = messageSource.getMessage("success.subjectsList", null, locale);
        return ApiResult.of(data, message);
    }
}

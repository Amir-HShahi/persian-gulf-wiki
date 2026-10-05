package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.article.dto.SubjectArticleResponse;
import com.persiangulfwiki.core.article.dto.UpdateMainArticleRequest;
import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.dto.SubjectInfoboxResponse;
import com.persiangulfwiki.core.subject.dto.SubjectListResponse;
import com.persiangulfwiki.core.subject.dto.SubjectLookupItemResponse;
import com.persiangulfwiki.core.subject.dto.SubjectMergeRequest;
import com.persiangulfwiki.core.subject.dto.SubjectMergeResponse;
import com.persiangulfwiki.core.subject.dto.SubjectReferencesResponse;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.service.SubjectDeletionService;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxService;
import com.persiangulfwiki.core.subject.service.SubjectListingService;
import com.persiangulfwiki.core.subject.service.SubjectLookupService;
import com.persiangulfwiki.core.subject.service.SubjectMergeService;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        + "comes from the articles bound to it. One of those is its main article, whose slug is the "
        + "subject's page: the first to be published, unless a moderator picks another. Reading is open "
        + "to everyone. There is no direct create or edit: a new subject is proposed, and an existing "
        + "one changed, through a subject revision that a moderator approves (see Subject Revisions). "
        + "Before proposing one, look it up (`GET /api/subjects/lookup`) to avoid a duplicate. "
        + "An article page shows the infobox of its `subjectId` from `GET /api/subjects/{subjectId}/infobox`; "
        + "an article with no subject (`subjectKind` null) has none.\n\n"
        + "**Removing subjects.** A moderator can merge a duplicate into another subject of the same kind, "
        + "which moves everything it had to that subject for good; its id then redirects there. A moderator "
        + "can also delete a subject no article is about, and restore it later; an admin can delete one "
        + "permanently. A deleted or merged-away subject is gone from every list, filter and infobox, "
        + "including as a value in other subjects' infoboxes, and reading it directly is a 404 -- except "
        + "`GET /api/subjects/{subjectId}`, which answers a merged-away id with the subject it went to.")
public class SubjectController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SubjectService subjectService;
    private final SubjectLookupService subjectLookupService;
    private final SubjectDeletionService subjectDeletionService;
    private final SubjectMergeService subjectMergeService;
    private final SubjectInfoboxService subjectInfoboxService;
    private final SubjectListingService subjectListingService;
    private final SubjectArticleService subjectArticleService;
    private final MessageSource messageSource;

    @Operation(summary = "Look up subjects by name", description = "Open to everyone; no account is required. The "
            + "duplicate check to run before proposing a subject: finds published subjects whose name in any language, "
            + "alias, or scientific name contains `q`. Matching ignores letter case, accents and Arabic diacritics, "
            + "spaces, the zero-width non-joiner and punctuation, and treats the Arabic and Persian forms of the same "
            + "letter (ي and ی, ك and ک, ة and ه) and Arabic, Persian and Latin digits as equal. Exact matches come "
            + "first, then names starting with `q`, then the rest, newest first within each. Each subject appears "
            + "once, with what it matched on. Deleted subjects, and proposals not yet approved, are not searched. "
            + "`name` and `slug` are in the reader's language (`Accept-Language`: fa, en or ar; anything else gets "
            + "fa), falling back to Farsi.")
    @ApiResponse(responseCode = "200", description = "Up to `size` matching subjects, best match first; an empty list "
            + "when nothing matches.")
    @ApiResponse(responseCode = "400", description = "`q` is missing (code MISSING_SERVLET_REQUEST_PARAMETER); `q` is "
            + "blank or not 2 to 100 characters long, or `size` is out of range (VALIDATION_FAILED with an `errors` "
            + "entry per parameter); or `kind` names a kind that does not exist (code INVALID_SUBJECT_KIND).",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "q", required = true, description = "The name to look for, 2 to 100 characters, e.g. `قشم`.")
    @Parameter(name = "kind", description = "Optional: only subjects of this kind -- ISLAND, PORT, OIL_FIELD, SPECIES, "
            + "STRAIT, FISHING_GROUND or PROVINCE (case-insensitive).")
    @Parameter(name = "size", description = "How many subjects to return at most, 1 to 100.")
    @GetMapping("/lookup")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SubjectLookupItemResponse>> lookup(
            @RequestParam @NotBlank(message = "{validation.subjectLookup.queryRequired}")
            @Size(min = 2, max = 100, message = "{validation.subjectLookup.queryLength}") String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Locale locale = LocaleContextHolder.getLocale();
        List<SubjectLookupItemResponse> data = subjectLookupService.lookup(q, kind,
                SupportedLocales.normalize(locale).getLanguage(), size);
        String message = messageSource.getMessage("success.subjectLookup", null, locale);
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a subject by id", description = "Open to everyone; no account is required. The id of a "
            + "subject that was merged into another answers with that other subject, `redirectedFrom` set to the id "
            + "asked for; the client should switch to the returned `id`.")
    @ApiResponse(responseCode = "200", description = "The subject, including any detail recorded for its kind.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id, or it has been deleted.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
    @ApiResponse(responseCode = "404", description = "No subject with that id, or it has been deleted or merged into another.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
            + "`isFilterable` true). Only published data is filtered on; edits awaiting review never count. Deleted subjects are "
            + "never listed or counted, and a filter naming a subject that was merged into another selects that other "
            + "subject.\n\n"
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

    @Operation(summary = "List a subject's articles", description = "Open to everyone; no account is required. The "
            + "subject's published articles -- those with at least one approved translation -- with the main article "
            + "first, then oldest first. Each is shown through one approved translation: the one in the reader's "
            + "language (`Accept-Language`: fa, en or ar; anything else gets fa), else Farsi, else the article's own "
            + "language, else any other; `isFallback` is true when it is not the reader's. Drafts and unapproved "
            + "translations never show here.")
    @ApiResponse(responseCode = "200", description = "One page of articles. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or `page`/`size` is out of "
            + "range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id, or it has been deleted or merged into another.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping("/{subjectId}/articles")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SubjectArticleResponse>> listArticles(
            @PathVariable UUID subjectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Locale locale = LocaleContextHolder.getLocale();
        List<SubjectArticleResponse> data = subjectArticleService.list(subjectId,
                SupportedLocales.normalize(locale).getLanguage(), PageRequest.of(page, size));
        String message = messageSource.getMessage("success.subjectArticlesList", null, locale);
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Set a subject's main article", description = "Moderators only. Makes `articleId` the "
            + "subject's main article, whose slug becomes the subject's page, and demotes the previous one. Takes "
            + "effect at once, with no review step; every switch is recorded in the audit log. The article must be "
            + "about this subject and have at least one approved translation. Choosing the article that is already "
            + "main changes nothing. Returns the main article, shown through a translation picked as in \"List a "
            + "subject's articles\".")
    @ApiResponse(responseCode = "200", description = "The subject's main article.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID; or `articleId` is missing, "
            + "names no article, names an article about another subject, or names one with no approved translation "
            + "(VALIDATION_FAILED with an `errors` entry on `articleId`).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, its email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The subject has been deleted or merged into another (code "
            + "SUBJECT_DELETED).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PatchMapping("/{subjectId}/main-article")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectArticleResponse> updateMainArticle(
            @PathVariable UUID subjectId, @Valid @RequestBody UpdateMainArticleRequest request) {
        Locale locale = LocaleContextHolder.getLocale();
        SubjectArticleResponse data = subjectArticleService.switchMain(currentUserId(), subjectId, request.articleId(),
                SupportedLocales.normalize(locale).getLanguage());
        String message = messageSource.getMessage("success.subjectMainArticleUpdated", null, locale);
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Delete a subject", description = "Moderators only. Hides the subject everywhere: it leaves "
            + "every list and filter, its infobox and other reads of it answer 404, and other subjects' infoboxes stop "
            + "showing it as a value. Nothing is erased, and `POST /api/subjects/{subjectId}/restorations` brings it all "
            + "back. Not allowed while any article is about the subject: merge it into another subject instead. "
            + "Every delete is recorded in the audit log.")
    @ApiResponse(responseCode = "204", description = "The subject was deleted.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, its email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The subject is already deleted or was merged into another (code "
            + "SUBJECT_DELETED), or an article is about it (code SUBJECT_HAS_ARTICLES).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @DeleteMapping("/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID subjectId) {
        subjectDeletionService.softDelete(currentUserId(), subjectId);
    }

    @Operation(summary = "Restore a deleted subject", description = "Moderators only. Undoes a delete: the subject, its "
            + "facts and the values pointing at it in other subjects' infoboxes all show again. A subject that was "
            + "merged into another cannot be restored. Every restore is recorded in the audit log.")
    @ApiResponse(responseCode = "200", description = "The restored subject.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, its email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The subject is not deleted (code SUBJECT_NOT_DELETED), or it was "
            + "merged into another, which cannot be undone (code SUBJECT_MERGED).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PostMapping("/{subjectId}/restorations")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectResponse> restore(@PathVariable UUID subjectId) {
        SubjectResponse data = subjectDeletionService.restore(currentUserId(), subjectId);
        String message = messageSource.getMessage("success.subjectRestored", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Merge a subject into another", description = "Moderators only. For a duplicate: the subject in "
            + "the path (the source) is merged into `targetId` (the target), of the same kind, at once and for good. "
            + "The target gets a new published revision holding what both said: its own names, with any language it "
            + "lacked taken from the source; the source's names and aliases as aliases, so a lookup still finds it by "
            + "them; and for each field, its own values plus the source's that differ -- on a single-value field the "
            + "source's come in as non-preferred, so the target's value stays the one shown. Its geometry and main "
            + "image are kept, or taken from the source when it had none. The source's articles, with their "
            + "galleries, move to the target; the target keeps its main article, or takes the source's if it had "
            + "none. Other subjects' values pointing at the source now point at the target. The source is then "
            + "deleted, and its id redirects: `GET /api/subjects/{sourceId}` answers with the target, and article "
            + "links to it show the target. Edits of the target in progress must be redone from its new revision, "
            + "and the target's expert verification goes stale. Every merge is recorded in the audit log.")
    @ApiResponse(responseCode = "201", description = "The merge: both ids and the target's new revision.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or the body is malformed "
            + "(code HTTP_MESSAGE_NOT_READABLE); or `targetId` is missing, names no subject, names a deleted one, or "
            + "names the source itself (VALIDATION_FAILED with an `errors` entry on `targetId`).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, its email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with the id in the path.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The source is already deleted or merged (code SUBJECT_DELETED); the "
            + "two are of different kinds (code SUBJECT_KIND_MISMATCH); or what they say together breaks the field "
            + "rules -- e.g. a field became required that neither has, or together they have too many aliases (code "
            + "SUBJECT_MERGE_INVALID). The last one carries an `errors` array like a validation failure, each `field` "
            + "a path into the combined revision (e.g. `payload.aliases.fa`); fix it by editing the target or the "
            + "source, then merge again.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PostMapping("/{sourceId}/merges")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<SubjectMergeResponse> merge(@PathVariable UUID sourceId,
            @Valid @RequestBody SubjectMergeRequest request) {
        SubjectMergeResponse data = subjectMergeService.merge(currentUserId(), sourceId, request.targetId());
        String message = messageSource.getMessage("success.subjectMerged", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List the subjects referring to a subject", description = "Open to everyone; no account is "
            + "required. The subjects whose published value of the field `attribute` points at this subject -- e.g. "
            + "`attribute=province` on a province lists its islands, ports and other places. Only a field's preferred "
            + "values count, as in the infobox and the filters. Newest first, shown as listing cards in the reader's "
            + "language (`Accept-Language`: fa, en or ar; anything else gets fa); the labels for `badges` codes come "
            + "from `GET /api/subject-kinds/{kind}/attributes`. Edits awaiting review never count.")
    @ApiResponse(responseCode = "200", description = "One page of referring subjects and their total. An out-of-range page "
            + "has no items but still has its total.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID; `attribute` is missing; "
            + "`kind` names a kind that does not exist (code INVALID_SUBJECT_KIND); `page`/`size` is out of range; or "
            + "`attribute` names no field, a field that does not refer to subjects, or one that refers to another kind "
            + "of subject than this one (VALIDATION_FAILED with an `errors` entry on `attribute`).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id, or it has been deleted or merged into another.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "attribute", required = true, description = "Key of a field referring to subjects of this subject's "
            + "kind, e.g. `province` or `located_in`; see `GET /api/subject-kinds/{kind}/attributes`.")
    @Parameter(name = "kind", description = "Optional: only referring subjects of this kind -- ISLAND, PORT, OIL_FIELD, "
            + "SPECIES, STRAIT, FISHING_GROUND or PROVINCE (case-insensitive).")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping("/{subjectId}/referenced-by")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectReferencesResponse> listReferencedBy(
            @PathVariable UUID subjectId,
            @RequestParam String attribute,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Locale locale = LocaleContextHolder.getLocale();
        SubjectReferencesResponse data = subjectListingService.referencedBy(subjectId, attribute, kind,
                SupportedLocales.normalize(locale).getLanguage(), PageRequest.of(page, size));
        String message = messageSource.getMessage("success.subjectReferencesList", null, locale);
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}

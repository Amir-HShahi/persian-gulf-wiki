package com.persiangulfwiki.core.article.controller;

import com.persiangulfwiki.core.article.dto.ArticleResponse;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.dto.RevisionResponse;
import com.persiangulfwiki.core.article.dto.TranslationResponse;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.article.service.ArticleService;
import com.persiangulfwiki.core.article.service.ArticleTranslationService;
import com.persiangulfwiki.core.common.dto.ApiResult;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.UUID;

// Deliberately NOT annotated @Validated -- same reasoning as SubjectController/
// SourceController: it would turn constraint failures on @RequestParam/@PathVariable into an
// unhandled AOP exception (500) instead of the 400 Spring's own method-validation handling
// already produces without it. See those classes' comments.
//
// Every mutation here requires only an authenticated, email-verified account -- no
// hasRole(...) check, unlike POST /api/subjects. That's deliberate: moderation review
// (Phase 3) is what gates an article's content from being publicly visible, not authorship.
// If only moderators could create/edit articles, the moderation pipeline would be moderators
// reviewing and approving their own drafts, which defeats the point of having a review step
// at all. Reading is open to everyone; writing requires nothing more than a verified account.
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
@Tag(name = "Articles", description = "The wiki entries. An article carries identity and typed classification only -- "
        + "its actual text lives per language in its translations, and each translation's "
        + "content history lives in its revisions. Nothing here is publish-visible on its own; "
        + "moderation status lives entirely on the revision. Reading is open to everyone; any "
        + "signed-in, verified account may write, since moderation review -- not authorship -- "
        + "is what gates publication.")
public class ArticleController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ArticleService articleService;
    private final ArticleTranslationService articleTranslationService;
    private final ArticleRevisionService articleRevisionService;
    private final MessageSource messageSource;

    @Operation(summary = "Create an article", description = "Creates the article together with its first translation (in `canonicalLanguage`) "
            + "and that translation's first draft revision, in a single step -- there is no "
            + "separate call to add the initial content afterwards. When `subjectId` is given, "
            + "`entityType` is derived from that subject and must be omitted; when `subjectId` "
            + "is omitted, `entityType` must be sent as GENERIC. `slug` must not already be in "
            + "use by any translation of any article.")
    @ApiResponse(responseCode = "201", description = "The created article.")
    @ApiResponse(responseCode = "400", description = "One of: field validation failed (see `errors`); `entityType` was supplied "
            + "together with `subjectId`; `entityType` is missing or is not GENERIC when "
            + "`subjectId` is omitted.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie -- both surface as a plain 403; read `detail` "
            + "for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "`subjectId` was given but no subject with that id exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "`slug` is already in use by another translation.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ArticleResponse> create(@Valid @RequestBody CreateArticleRequest request) {
        ArticleResponse data = articleService.create(currentUserId(), request);
        String message = messageSource.getMessage("success.articleCreated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get an article by id", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "The article.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{articleId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ArticleResponse> get(@PathVariable UUID articleId) {
        ArticleResponse data = articleService.get(articleId);
        String message = messageSource.getMessage("success.articleFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List articles", description = "Open to everyone; no account is required. Optionally filtered by subject and/or "
            + "typed classification, and paged.")
    @ApiResponse(responseCode = "200", description = "One page of articles. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "`entityType` is not one of ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, GENERIC "
            + "(case-insensitive), or `page`/`size` is outside its allowed range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "subjectId", description = "Optional filter: only articles bound to this subject.")
    @Parameter(name = "entityType", description = "Optional filter: ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, or GENERIC (case-insensitive).")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<ArticleResponse>> list(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        List<ArticleResponse> data = articleService.list(subjectId, entityType, PageRequest.of(page, size));
        String message = messageSource.getMessage("success.articlesList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Add a translation to an article", description = "Adds a new language to an existing article, creating that language's first draft "
            + "revision in the same step. `slug` must not already be in use by any translation "
            + "of any article.")
    @ApiResponse(responseCode = "201", description = "The created translation.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The article already has a translation for this `language`, or `slug` is already "
            + "in use by another translation.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/{articleId}/translations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<TranslationResponse> createTranslation(
            @PathVariable UUID articleId, @Valid @RequestBody CreateTranslationRequest request) {
        TranslationResponse data = articleTranslationService.addTranslation(currentUserId(), articleId, request);
        String message = messageSource.getMessage("success.translationCreated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a translation", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "The translation.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "language", description = "BCP-47 language tag, e.g. \"fa\", \"en\", \"ar\".")
    @GetMapping("/{articleId}/translations/{language}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<TranslationResponse> getTranslation(@PathVariable UUID articleId, @PathVariable String language) {
        TranslationResponse data = articleTranslationService.get(articleId, language);
        String message = messageSource.getMessage("success.translationFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Update a draft revision", description = "Replaces `title`, `body` and `summary` on an existing revision in place. Only "
            + "allowed while the revision's status is DRAFT or CHANGES_REQUESTED, and only by "
            + "the account that authored it.")
    @ApiResponse(responseCode = "200", description = "The updated revision.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the caller is not the revision's author, the account's email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie -- "
            + "read `detail`/`code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language, or the translation has no "
            + "revision with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The revision's status is not DRAFT or CHANGES_REQUESTED, so it can no longer "
            + "be edited.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PatchMapping("/{articleId}/translations/{language}/revisions/{revisionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<RevisionResponse> updateRevision(@PathVariable UUID articleId, @PathVariable String language,
            @PathVariable UUID revisionId, @Valid @RequestBody UpdateRevisionRequest request) {
        RevisionResponse data = articleRevisionService.update(currentUserId(), articleId, language, revisionId, request);
        String message = messageSource.getMessage("success.revisionUpdated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List a translation's revisions", description = "Open to everyone; no account is required. Ordered oldest first.")
    @ApiResponse(responseCode = "200", description = "Every revision of this translation, including drafts and rejected ones.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{articleId}/translations/{language}/revisions")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<RevisionResponse>> listRevisions(@PathVariable UUID articleId, @PathVariable String language) {
        List<RevisionResponse> data = articleRevisionService.list(articleId, language);
        String message = messageSource.getMessage("success.revisionsList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a revision by id", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "The revision.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language, or the translation has no "
            + "revision with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{articleId}/translations/{language}/revisions/{revisionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<RevisionResponse> getRevision(
            @PathVariable UUID articleId, @PathVariable String language, @PathVariable UUID revisionId) {
        RevisionResponse data = articleRevisionService.get(articleId, language, revisionId);
        String message = messageSource.getMessage("success.revisionFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Submit a revision for review", description = "Moves the revision from DRAFT or CHANGES_REQUESTED to PENDING. This only records "
            + "that the author considers it ready for review -- it does not itself trigger a "
            + "moderation decision. Only the revision's author may submit it.")
    @ApiResponse(responseCode = "200", description = "The revision, now PENDING.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the caller is not the revision's author, the account's email address is "
            + "not yet verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language, or the translation has no "
            + "revision with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The revision's status is not DRAFT or CHANGES_REQUESTED, so it cannot be "
            + "submitted.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/{articleId}/translations/{language}/revisions/{revisionId}/submit")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<RevisionResponse> submitRevision(
            @PathVariable UUID articleId, @PathVariable String language, @PathVariable UUID revisionId) {
        RevisionResponse data = articleRevisionService.submit(currentUserId(), articleId, language, revisionId);
        String message = messageSource.getMessage("success.revisionSubmitted", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

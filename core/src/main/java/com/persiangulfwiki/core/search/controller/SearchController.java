package com.persiangulfwiki.core.search.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.search.dto.SearchResultsResponse;
import com.persiangulfwiki.core.search.dto.SearchSuggestionResponse;
import com.persiangulfwiki.core.search.service.SearchService;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.web.ClientAddresses;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// No @Validated on the class, deliberately -- see ArticleController/SubjectController: the
// parameter constraints already produce the translated 400 without it, and with it they become a
// 500. Both routes are anonymous (PublicRoutes.CONTENT_READS) and rate-limited per client.
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Full-text search over published articles, in every language. Spelling variants "
        + "of Persian and Arabic letters (ی/ي, ک/ك, hamza forms), the zero-width non-joiner, diacritics and Persian "
        + "or Arabic digits all match each other, words match as prefixes while typing, English word forms match "
        + "for English articles, and misspelt titles still match when nothing matches exactly. An article is also "
        + "found by its subject's names in other languages, aliases, and the names of subjects merged into it. "
        + "Only published articles are searched, and the result is the same whoever asks. The index catches up "
        + "with newly approved or unpublished articles within about 30 seconds. Open to everyone, but limited per "
        + "client address: past the limit the API answers 429 with a Retry-After header.")
public class SearchController {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_PREFIX_LENGTH = 100;
    private static final int MAX_PAGE = 500;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SUGGESTIONS = 20;
    private static final String SUPPORTED_LANGUAGES = "fa|en|ar";

    private final SearchService searchService;
    private final MessageSource messageSource;

    @Operation(summary = "Search articles", description = "Published articles matching `q`, best match first: titles "
            + "and subject names rank above slug and summary, which rank above the body. When the search finds fewer "
            + "than 3 articles in all, `didYouMean` may offer a corrected query. Every field is plain text except "
            + "each hit's `snippet`, which is safe HTML.")
    @ApiResponse(responseCode = "200", description = "One page of results, the total across all pages, and possibly a "
            + "corrected query. A page past the end has an empty `items` list, not an error.")
    @ApiResponse(responseCode = "400", description = "`q` is missing, blank or longer than 200 characters; "
            + "`language` is not fa, en or ar; `subjectKind` is not one of ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, "
            + "FISHING_GROUND, PROVINCE (case-insensitive); or `page`/`size` is outside its allowed range.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many searches from this address in the current period. "
            + "`Retry-After` gives the seconds until the next period starts.",
            headers = @Header(name = "Retry-After", description = "Seconds until searching is allowed again.",
                    schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "q", description = "What to search for, as the reader typed it. 1 to 200 characters.",
            example = "تنگه هرمز")
    @Parameter(name = "language", description = "Only translations in this language: fa, en or ar. Every language "
            + "when left out.")
    @Parameter(name = "subjectKind", description = "Optional filter: only articles whose subject is of this kind "
            + "(case-insensitive). Articles with no subject never match it.\n\n" + SubjectKind.VALUES_DOC)
    @Parameter(name = "page", description = "Zero-based page index, at most 500.")
    @Parameter(name = "size", description = "Page size, 1 to 50.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SearchResultsResponse> search(
            @RequestParam @NotBlank(message = "{validation.searchQuery.required}")
                    @Size(max = MAX_QUERY_LENGTH, message = "{validation.searchQuery.tooLong}") String q,
            @RequestParam(required = false) @Pattern(regexp = SUPPORTED_LANGUAGES,
                    message = "{validation.searchLanguage.invalid}") @Nullable String language,
            @RequestParam(required = false) @Nullable String subjectKind,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(hidden = true) HttpServletRequest request) {
        SearchResultsResponse data =
                searchService.search(ClientAddresses.rateLimitBucket(request), q, language, subjectKind, page, size);
        String message = messageSource.getMessage("success.searchResults", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Suggest articles while typing", description = "Published articles whose title, or another "
            + "name of their subject, starts with what has been typed so far, every word matched as a prefix. Titles "
            + "that start with the text come first, then other names that do, then shorter titles before longer "
            + "ones. Cheap enough to call on every keystroke, and allowed far more often than a full search.")
    @ApiResponse(responseCode = "200", description = "Up to `limit` suggestions; empty when nothing matches.")
    @ApiResponse(responseCode = "400", description = "`prefix` is missing, blank or longer than 100 characters; "
            + "`language` is not fa, en or ar; or `limit` is outside 1 to 20.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Too many suggestion requests from this address in the current "
            + "period. `Retry-After` gives the seconds until the next period starts.",
            headers = @Header(name = "Retry-After", description = "Seconds until suggestions are allowed again.",
                    schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "prefix", in = ParameterIn.QUERY, description = "The text typed so far. 1 to 100 characters.",
            example = "هرم")
    @Parameter(name = "language", description = "Only translations in this language: fa, en or ar. Every language "
            + "when left out.")
    @Parameter(name = "limit", description = "How many suggestions at most, 1 to 20.")
    @GetMapping("/suggestions")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SearchSuggestionResponse>> suggest(
            @RequestParam @NotBlank(message = "{validation.searchPrefix.required}")
                    @Size(max = MAX_PREFIX_LENGTH, message = "{validation.searchPrefix.tooLong}") String prefix,
            @RequestParam(required = false) @Pattern(regexp = SUPPORTED_LANGUAGES,
                    message = "{validation.searchLanguage.invalid}") @Nullable String language,
            @RequestParam(defaultValue = "8") @Min(1) @Max(MAX_SUGGESTIONS) int limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        List<SearchSuggestionResponse> data =
                searchService.suggest(ClientAddresses.rateLimitBucket(request), prefix, language, limit);
        String message = messageSource.getMessage("success.searchSuggestions", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }
}

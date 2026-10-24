package com.persiangulfwiki.core.article.controller;

import com.persiangulfwiki.core.article.dto.CreateFeaturedArticleRequest;
import com.persiangulfwiki.core.article.dto.CurrentFeaturedArticleResponse;
import com.persiangulfwiki.core.article.dto.FeaturedArticleResponse;
import com.persiangulfwiki.core.article.service.FeaturedArticleService;
import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.config.SupportedLocales;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Deliberately NOT annotated @Validated -- same reasoning as ArticleController.
//
// GET /current is public (PublicRoutes.CONTENT_READS); everything else is admin-only. Choosing
// what the whole site leads with is an editorial decision for the site's owners, not part of
// moderation, so moderators are refused here even though they pass hasRole('MODERATOR').
@RestController
@RequestMapping("/api/featured-articles")
@RequiredArgsConstructor
@Tag(name = "Featured Articles", description = "The article the home page leads with. Admins schedule an article for a "
        + "time window; the home page shows the article of the most recently started window that is live now and "
        + "published in the reader's language, or, when there is none, the newest published article in that "
        + "language.")
public class FeaturedArticleController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FeaturedArticleService featuredArticleService;
    private final MessageSource messageSource;

    @Operation(summary = "Get the home page's featured article", description = "Open to everyone; no account is "
            + "required, and the result is the same whoever asks. Only approved content is shown: `title` and "
            + "`summary` come from the translation's most recently approved revision. A scheduled article with no "
            + "approved translation in `language` is skipped in favour of the next live window, then of the newest "
            + "published article. `mainImage` is the main image of the article's subject.")
    @ApiResponse(responseCode = "200", description = "The article to feature.")
    @ApiResponse(responseCode = "400", description = "`language` is missing, blank or longer than 20 characters (see "
            + "`errors`).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article is published in `language` at all, so there is "
            + "nothing to feature.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "language", required = true, description = "BCP-47 language tag of the translation to show, "
            + "e.g. \"fa\", \"en\", \"ar\". Matched exactly.")
    @GetMapping("/current")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<CurrentFeaturedArticleResponse> getCurrent(
            @RequestParam @NotBlank(message = "{validation.language.required}")
                    @Size(max = 20, message = "{validation.language.tooLong}") String language) {
        CurrentFeaturedArticleResponse data = featuredArticleService.getCurrent(language);
        String message = messageSource.getMessage("success.featuredArticleFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List featured-article windows", description = "Admins only. Every scheduled window, past, "
            + "live and upcoming, latest start first. Each window's `article` is shown in the reader's language "
            + "(`Accept-Language`), with its text null while the article has nothing approved.")
    @ApiResponse(responseCode = "200", description = "One page of windows, with the total across all pages. An "
            + "out-of-range page has an empty `items` list, not an error.")
    @ApiResponse(responseCode = "400", description = "`page` or `size` is outside its allowed range.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not an admin, or the account's email address is "
            + "not yet verified; read `detail` for which one occurred.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<PageResponse<FeaturedArticleResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        PageResponse<FeaturedArticleResponse> data =
                featuredArticleService.list(PageRequest.of(page, size), readerLanguage());
        String message = messageSource.getMessage("success.featuredArticlesList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Schedule a featured article", description = "Admins only. Features `articleId` from "
            + "`startsAt` (default now) until `endsAt` (default: no end). Windows may overlap; the one that started "
            + "most recently wins. An article with nothing approved yet may be scheduled: it is simply not shown "
            + "until it has an approved translation in the reader's language.")
    @ApiResponse(responseCode = "201", description = "The created window.")
    @ApiResponse(responseCode = "400", description = "Field validation failed (see `errors`): `articleId` is missing, "
            + "or `endsAt` is not after `startsAt`.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not an admin, the account's email address is not "
            + "yet verified, or the CSRF header is missing or does not match the cookie; read `detail` for which "
            + "one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article with that `articleId`.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET "
            + "/api/auth/csrf first to receive the XSRF-TOKEN cookie, then encode its value and send the encoded "
            + "result in this header — see the API description above for the required encoding algorithm; sending "
            + "the raw cookie value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<FeaturedArticleResponse> create(@Valid @RequestBody CreateFeaturedArticleRequest request) {
        FeaturedArticleResponse data = featuredArticleService.create(currentUserId(), request, readerLanguage());
        String message = messageSource.getMessage("success.featuredArticleCreated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Remove a featured-article window", description = "Admins only. Takes effect immediately: if "
            + "it was the live window, the home page falls back to the next live one, or to the newest published "
            + "article.")
    @ApiResponse(responseCode = "204", description = "The window was removed.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not an admin, the account's email address is not "
            + "yet verified, or the CSRF header is missing or does not match the cookie; read `detail` for which "
            + "one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No window with that id.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET "
            + "/api/auth/csrf first to receive the XSRF-TOKEN cookie, then encode its value and send the encoded "
            + "result in this header — see the API description above for the required encoding algorithm; sending "
            + "the raw cookie value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{featuredArticleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID featuredArticleId) {
        featuredArticleService.delete(currentUserId(), featuredArticleId);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }

    // fa, en or ar from Accept-Language, else fa: the language each window's article is shown in.
    private static String readerLanguage() {
        return SupportedLocales.normalize(LocaleContextHolder.getLocale()).getLanguage();
    }
}

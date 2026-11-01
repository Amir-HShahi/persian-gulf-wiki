package com.persiangulfwiki.core.user.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.user.dto.ContributedArticleResponse;
import com.persiangulfwiki.core.user.dto.PublicProfileResponse;
import com.persiangulfwiki.core.user.service.UserProfileService;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Public, under its own path rather than /api/users/{username}: opening GET /api/users/** to
// anonymous callers would open /api/users/me and /me/sessions with it.
//
// No @Validated on the class: on this Spring version it turns a failed @Min/@Max on a parameter
// into a 500 instead of a 400 (see SubjectController).
@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
@Tag(name = "Profiles", description = "Public user profiles and the articles each user contributed to. No account needed.")
public class ProfileController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserProfileService userProfileService;
    private final MessageSource messageSource;

    @Operation(summary = "Get a user's public profile", description = "Open to everyone. A profile is public only once its "
            + "user has set both a full name and an avatar.")
    @ApiResponse(responseCode = "200", description = "The profile.")
    @ApiResponse(responseCode = "404", description = "No user with that username, or the account is disabled, or the user has "
            + "not set both a full name and an avatar yet -- indistinguishable on purpose.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "username", description = "Case-insensitive.")
    @GetMapping("/{username}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<PublicProfileResponse> get(@PathVariable String username) {
        PublicProfileResponse data = userProfileService.getPublicProfile(username);
        String message = messageSource.getMessage("success.profileFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List the articles a user contributed to", description = "Open to everyone. One card per article, "
            + "newest contribution first, with every kind of contribution the user made to it. Only public articles "
            + "and approved or published work count. Card text follows `Accept-Language` (fa, en or ar).")
    @ApiResponse(responseCode = "200", description = "A page of contributed articles; `total` counts articles, not contributions.")
    @ApiResponse(responseCode = "400", description = "`page` below 0, or `size` outside 1 to 100.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No public profile for that username (see GET /api/profiles/{username}).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "username", description = "Case-insensitive.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping("/{username}/contributed-articles")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<PageResponse<ContributedArticleResponse>> contributedArticles(
            @PathVariable String username,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        String language = SupportedLocales.normalize(LocaleContextHolder.getLocale()).getLanguage();
        PageResponse<ContributedArticleResponse> data = userProfileService.getContributedArticles(username, language,
                PageRequest.of(page, size));
        String message = messageSource.getMessage("success.contributedArticlesList", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }
}

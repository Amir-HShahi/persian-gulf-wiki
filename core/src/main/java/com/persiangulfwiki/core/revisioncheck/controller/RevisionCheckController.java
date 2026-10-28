package com.persiangulfwiki.core.revisioncheck.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.revisioncheck.dto.RevisionChecksResponse;
import com.persiangulfwiki.core.revisioncheck.service.RevisionCheckService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Nested under the revision it describes because a check cannot exist without one, even though
// the feature is its own package. A signed-in route despite the GET: PublicRoutes carves it out
// of the anonymous article reads.
@RestController
@RequiredArgsConstructor
@Tag(name = "Revision Checks", description = "Automatic checks run on every submitted article revision: suggested "
        + "text fixes (for example an Arabic ي in a Persian article, or a prefix written apart from its verb) and "
        + "a dead-link check of the body's external links. They run in the background after each submit, usually "
        + "within seconds (links can take a minute or more), and are advisory only: they never block a moderation "
        + "decision. Visible to the revision's author and to moderators; moderators also get them with the "
        + "revision's moderation task.")
public class RevisionCheckController {

    private static final String MODERATOR_AUTHORITY = "ROLE_MODERATOR";

    private final RevisionCheckService revisionCheckService;
    private final MessageSource messageSource;
    private final RoleHierarchy roleHierarchy;

    @Operation(summary = "Get a revision's checks", description = "The latest results of both checks on this revision. "
            + "Each check is PENDING right after a submit and moves to DONE (findings ready), REJECTED or FAILED; poll "
            + "this route to follow it. Each submit replaces the previous results. Both checks are null for a "
            + "revision never submitted since the checks were introduced.")
    @ApiResponse(responseCode = "200", description = "The revision's checks.")
    @ApiResponse(responseCode = "400", description = "The article or revision id in the path is not a valid UUID.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the caller can read the revision (it is approved) but "
            + "is neither its author nor a moderator/admin, or the account's email address is not yet verified -- "
            + "read `code` for which one occurred.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no translation for this language, the "
            + "translation has no revision with this id, or the revision is not approved and the caller is neither "
            + "its author nor a moderator/admin -- indistinguishable on purpose.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @SecurityRequirement(name = "cookieAuth")
    @GetMapping("/api/articles/{articleId}/translations/{language}/revisions/{revisionId}/checks")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<RevisionChecksResponse> get(@PathVariable UUID articleId, @PathVariable String language,
            @PathVariable UUID revisionId) {
        RevisionChecksResponse data = revisionCheckService.getForCaller(currentUserId(), isCurrentUserModerator(),
                articleId, language, revisionId);
        String message = messageSource.getMessage("success.revisionChecksFetched", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }

    // Resolved through the RoleHierarchy so ADMIN counts as a moderator, as in ArticleController.
    private boolean isCurrentUserModerator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .anyMatch(authority -> MODERATOR_AUTHORITY.equals(authority.getAuthority()));
    }
}

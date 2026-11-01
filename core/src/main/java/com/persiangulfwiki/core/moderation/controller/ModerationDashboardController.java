package com.persiangulfwiki.core.moderation.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.moderation.dto.DashboardRange;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardArticlesResponse;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardSummaryResponse;
import com.persiangulfwiki.core.moderation.service.ModerationDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Not @Validated, for the same reason as ModerationController. Shares its "Moderation" tag via
// @Operation(tags) rather than a second @Tag, so the tag keeps a single description and
// OpenApiConfig.TAG_ORDER needs no new entry. MODERATOR-gated like the rest of /api/moderation;
// admins pass through RoleHierarchyConfig.
@RestController
@RequestMapping("/api/moderation/dashboard")
@RequiredArgsConstructor
public class ModerationDashboardController {

    private static final String TAG = "Moderation";

    private final ModerationDashboardService moderationDashboardService;
    private final MessageSource messageSource;

    @Operation(tags = TAG, summary = "Get today's review figures", description = "Today's counts for the dashboard cards, "
            + "each with its change from yesterday. Days are Tehran calendar days. Only article revisions are "
            + "counted -- gallery items and subject proposals are not. A revision is pending if it was first "
            + "submitted that day and is still waiting for review (or was sent back for changes); approved and "
            + "rejected count the decisions made that day.")
    @ApiResponse(responseCode = "200", description = "Today's counts.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator or admin role, or its email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @GetMapping("/summary")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ModerationDashboardSummaryResponse> summary() {
        String message = messageSource.getMessage("success.moderationDashboardSummary", null, LocaleContextHolder.getLocale());
        return ApiResult.of(moderationDashboardService.summary(), message);
    }

    @Operation(tags = TAG, summary = "Get article review figures over a range", description = "Approvals per day for the "
            + "dashboard chart and the status split for its donut, over a rolling range that always ends today "
            + "(Tehran time). The split covers article revisions first submitted within the range, by where they "
            + "stand now.")
    @ApiResponse(responseCode = "200", description = "The per-day approvals and the status split for the range.")
    @ApiResponse(responseCode = "400", description = "`range` is not one of the accepted values.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator or admin role, or its email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "range", description = "Optional, case-insensitive; defaults to `LAST_7_DAYS`.\n\n"
            + DashboardRange.VALUES_DOC)
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @GetMapping("/articles")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ModerationDashboardArticlesResponse> articles(@RequestParam(required = false) String range) {
        String message = messageSource.getMessage("success.moderationDashboardArticles", null, LocaleContextHolder.getLocale());
        return ApiResult.of(moderationDashboardService.articles(range), message);
    }
}

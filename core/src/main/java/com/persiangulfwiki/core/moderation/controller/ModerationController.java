package com.persiangulfwiki.core.moderation.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.dto.ModerationTaskResponse;
import com.persiangulfwiki.core.moderation.service.ModerationService;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

// Deliberately NOT annotated @Validated -- same reasoning as ArticleController/
// SubjectController/SourceController: it would turn constraint failures on
// @RequestParam/@PathVariable into an unhandled AOP exception (500) instead of the 400
// Spring's own method-validation handling already produces without it.
//
// Mounted at /api/moderation rather than nested under /api/articles/... on purpose. A
// moderator works a single cross-article queue -- "what needs reviewing anywhere on the
// wiki" -- and does not start from an article they already have in hand, so nesting these
// under a specific article would make the one access pattern that matters impossible to
// express.
//
// Nothing here is public. Unlike articles (where every GET is open), even reading the queue
// is MODERATOR-only: it exposes unpublished drafts and the editorial reasoning about them.
// No SecurityConfig change was needed for that -- /api/moderation/** is not on any permitAll
// matcher, so it falls through to anyRequest().authenticated(), and the role check below
// narrows it further. Adding a GET matcher for this path to SecurityConfig, as was done for
// subjects/sources/articles, would be a mistake.
@RestController
@RequestMapping("/api/moderation")
@RequiredArgsConstructor
@Tag(name = "Moderation", description = "The editorial review queue. A moderator claims a task, reads the revision it points at, "
        + "and records a decision on it. Moderators judge policy -- whether the content belongs on "
        + "the wiki at all -- not whether its facts are correct, which is a separate advisory "
        + "review by subject-matter experts. Every endpoint here requires the moderator role, "
        + "including reading: the queue exposes unpublished drafts.")
public class ModerationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ModerationService moderationService;
    private final MessageSource messageSource;

    @Operation(summary = "List the review queue", description = "One page of moderation tasks across every article, optionally filtered by state. "
            + "Each task carries its full decision history, oldest first -- a task that has been "
            + "sent back to its author once before will already have a decision on it explaining "
            + "what was asked for.")
    @ApiResponse(responseCode = "200", description = "One page of tasks. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "`state` is not one of OPEN, CLAIMED, DECIDED (case-insensitive), or `page`/`size` is "
            + "outside its allowed range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, or its email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "state", description = "Optional filter: OPEN (waiting for a moderator to pick it up), CLAIMED (someone is "
            + "working on it), or DECIDED (finished). Case-insensitive. Omit for every state.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @GetMapping("/tasks")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<ModerationTaskResponse>> listTasks(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        List<ModerationTaskResponse> data = moderationService.list(state, PageRequest.of(page, size));
        String message = messageSource.getMessage("success.moderationTasksList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Claim a task", description = "Takes the task out of the open queue and assigns it to the calling moderator, who "
            + "from then on is the only account that may decide it. A task that has been sent "
            + "back to its author returns to the open queue and can be claimed again -- by anyone, "
            + "not necessarily whoever handled the previous round.")
    @ApiResponse(responseCode = "200", description = "The task, now claimed by the caller.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The account does not hold the moderator role, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No task with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The task is not open -- another moderator already holds it, or it has already been "
            + "decided.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PostMapping("/tasks/{taskId}/claim")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ModerationTaskResponse> claimTask(@PathVariable UUID taskId) {
        ModerationTaskResponse data = moderationService.claim(currentUserId(), taskId);
        String message = messageSource.getMessage("success.moderationTaskClaimed", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Decide a claimed task", description = "Records the outcome of one round of review, and is the only thing in the API that "
            + "can publish a revision. APPROVE makes the revision the translation's live content "
            + "and closes the task. REJECT closes the task permanently -- the author cannot edit "
            + "or resubmit that revision and must write a new one, which will open a new task. "
            + "REQUEST_CHANGES returns the revision to the author to fix and keeps this task "
            + "alive: it goes back to the open queue, and once the author resubmits it can be "
            + "claimed and decided again, accumulating a second entry in its decision history. "
            + "Only the moderator currently holding the task may call this.")
    @ApiResponse(responseCode = "200", description = "The task, with this decision appended to its history.")
    @ApiResponse(responseCode = "400", description = "One of: field validation failed (see `errors`); the id in the path is not a valid "
            + "UUID; or `reason` was omitted for a REJECT or REQUEST_CHANGES decision, where it is "
            + "required.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "One of: the caller is not the moderator holding this task (including the case where "
            + "nobody has claimed it yet); the account does not hold the moderator role; its email "
            + "address is not yet verified; or the CSRF header is missing or does not match the "
            + "cookie. Read `detail`/`code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No task with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Either the task has already been decided, or its revision is not awaiting review -- "
            + "which is what happens when changes were requested and the author has not resubmitted "
            + "yet.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('MODERATOR')")
    @PostMapping("/tasks/{taskId}/decide")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ModerationTaskResponse> decideTask(
            @PathVariable UUID taskId, @Valid @RequestBody DecideRequest request) {
        ModerationTaskResponse data = moderationService.decide(currentUserId(), taskId, request);
        String message =
                messageSource.getMessage("success.moderationDecisionRecorded", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

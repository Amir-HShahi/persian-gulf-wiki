package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.subject.dto.ExpertVerificationResponse;
import com.persiangulfwiki.core.subject.dto.VerificationQueueItemResponse;
import com.persiangulfwiki.core.subject.service.ExpertVerificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// hasRole('EXPERT_REVIEWER') turns away callers whose token holds no expert role at all; the
// service then checks the role's expertise against user_roles, which the token doesn't carry.
// Lives in the subject package rather than expertreviewer because the state it produces is read
// by the subject infobox. The queue sits under /api/expert-reviewer with the role's other routes.
// Not annotated @Validated -- see SubjectController.
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Expert Verifications", description = "Expert reviewers confirming that a published subject revision is "
        + "correct. This happens after publishing and never holds it up: moderators publish on policy alone. A "
        + "confirmation belongs to the revision it was made on and cannot be withdrawn. Once a newer revision is "
        + "approved, the subject reads as STALE (\"verified revision 3, current is 4\") until an expert confirms "
        + "the new one. Only an expert reviewer whose expertise is the subject's kind may confirm, and never a "
        + "revision they wrote themselves.")
public class ExpertVerificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String MODERATOR_AUTHORITY = "ROLE_MODERATOR";

    private static final String CSRF_DESCRIPTION = "CSRF token. Call GET /api/auth/csrf first to receive the "
            + "XSRF-TOKEN cookie, then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie value here is rejected.";

    private final ExpertVerificationService expertVerificationService;
    private final MessageSource messageSource;
    private final RoleHierarchy roleHierarchy;

    @Operation(summary = "Verify a subject revision", description = "Records that the caller, an expert reviewer, has "
            + "checked the revision readers currently see of a subject in their expertise and found it correct. The "
            + "subject then reads as VERIFIED until a newer revision is approved. No request body.")
    @ApiResponse(responseCode = "201", description = "The confirmation.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "One of: the account holds no expert reviewer role (code ACCESS_DENIED); "
            + "its expertise does not include the subject's kind, or the role was withdrawn since sign-in (code "
            + "NOT_SUBJECT_KIND_EXPERT); the caller wrote this revision (code SELF_VERIFICATION_NOT_ALLOWED); the "
            + "email address is not yet verified, or the CSRF header is missing or does not match the cookie.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No revision with that id, or an unapproved one the caller may not read "
            + "-- the two are deliberately indistinguishable.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "One of: the revision is not the one readers currently see -- a draft, "
            + "pending or rejected revision, or an older approved one (code SUBJECT_REVISION_NOT_LIVE); its subject has "
            + "been deleted or merged into another (code SUBJECT_DELETED); or the caller has already verified it (code "
            + "SUBJECT_REVISION_ALREADY_VERIFIED).",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = CSRF_DESCRIPTION)
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('EXPERT_REVIEWER')")
    @PostMapping("/subject-revisions/{revisionId}/verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ExpertVerificationResponse> verify(@PathVariable UUID revisionId) {
        ExpertVerificationResponse data =
                expertVerificationService.verify(currentUserId(), revisionId, isCurrentUserModerator());
        return ApiResult.of(data, message("success.expertVerificationCreated"));
    }

    @Operation(summary = "List subjects awaiting verification", description = "Subjects in the caller's expertise whose "
            + "current revision no expert has confirmed yet -- never confirmed (NONE), or confirmed only on an older "
            + "revision (STALE) -- newest approval first. Revisions the caller wrote are left out, since they may not "
            + "verify them.")
    @ApiResponse(responseCode = "200", description = "One page of subjects, with the total number of queued subjects across "
            + "all pages. An out-of-range page has an empty `items` list, not an error.")
    @ApiResponse(responseCode = "400", description = "`kind` is not one of ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, "
            + "FISHING_GROUND, PROVINCE (case-insensitive), or `page`/`size` is outside its allowed range.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "One of: the account holds no expert reviewer role (code ACCESS_DENIED); "
            + "`kind` is not in its expertise, it has no expertise at all, or the role was withdrawn since sign-in "
            + "(code NOT_SUBJECT_KIND_EXPERT); or the email address is not yet verified.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "kind", description = "Optional: only this kind, which must be one of the caller's areas of "
            + "expertise (case-insensitive). Omit for all of them.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @SecurityRequirement(name = "cookieAuth")
    @PreAuthorize("hasRole('EXPERT_REVIEWER')")
    @GetMapping("/expert-reviewer/verification-queue")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<PageResponse<VerificationQueueItemResponse>> queue(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        PageResponse<VerificationQueueItemResponse> data =
                expertVerificationService.queue(currentUserId(), kind, PageRequest.of(page, size));
        return ApiResult.of(data, message("success.verificationQueueList"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }

    // Through the RoleHierarchy, so ADMIN counts as a moderator -- see ArticleController. Only
    // decides whether an unapproved revision reads as 404 or 409; it grants nothing.
    private boolean isCurrentUserModerator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .anyMatch(authority -> MODERATOR_AUTHORITY.equals(authority.getAuthority()));
    }
}

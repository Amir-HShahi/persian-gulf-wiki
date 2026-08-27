package com.persiangulfwiki.core.expertreviewer.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.expertreviewer.dto.ApplicationRequest;
import com.persiangulfwiki.core.expertreviewer.dto.ApplicationResponse;
import com.persiangulfwiki.core.expertreviewer.dto.ReviewDecisionRequest;
import com.persiangulfwiki.core.expertreviewer.service.ExpertReviewerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/expert-reviewer")
@RequiredArgsConstructor
@Tag(name = "Expert Reviewer", description = "Application-based EXPERT_REVIEWER role: any contributor can apply for a "
        + "given entity type, an admin reviews and approves/rejects")
@SecurityRequirement(name = "cookieAuth")
public class ExpertReviewerController {

    private final ExpertReviewerService expertReviewerService;
    private final MessageSource messageSource;

    @Operation(summary = "Apply for the Expert Reviewer role", description = "Any authenticated user may apply, stating the entity type they "
            + "claim expertise in and a justification. Only one PENDING application per "
            + "(applicant, entityType) is allowed at a time.")
    @ApiResponse(responseCode = "201", description = "Application created with status PENDING. Body is "
            + "`{ \"data\": { id, applicantUserId, entityType, justification, status, createdAt, reviewedAt }, "
            + "\"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed summary string "
            + "(\"validation failed\") — the actual failures are in the `errors` array, one entry per field with "
            + "`field` and `message`. Both `entityType` and `justification` must be non-blank.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The authenticated account's email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A PENDING application for this (applicant, entityType) already exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ApplicationResponse> apply(@Valid @RequestBody ApplicationRequest request) {
        ApplicationResponse data = expertReviewerService.apply(currentUserId(), request);
        String message = messageSource.getMessage("success.expertReviewerApplied", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List expert reviewer applications", description = "Admin only. Optional status filter (PENDING/APPROVED/REJECTED); omitted returns all.")
    @ApiResponse(responseCode = "200", description = "List of applications. Body is "
            + "`{ \"data\": [ { id, applicantUserId, entityType, justification, status, createdAt, reviewedAt }, ... ], "
            + "\"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but not an admin, or its email address "
            + "is not yet verified — both surface as a plain 403 with no way to distinguish them from the "
            + "response's status code alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "`status` is not one of PENDING, APPROVED, or REJECTED (case-insensitive).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "status", description = "Optional filter: PENDING, APPROVED, or REJECTED (case-insensitive). Omit to return applications in every status.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/applications")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<ApplicationResponse>> listApplications(@RequestParam(required = false) String status) {
        List<ApplicationResponse> data = expertReviewerService.listApplications(status);
        String message = messageSource.getMessage("success.expertReviewerApplicationsList", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Approve or reject an expert reviewer application", description = "Admin only. Approving grants the EXPERT_REVIEWER role scoped to the "
            + "application's entityType, using the same grant mechanism as /api/admin/users/{id}/role. "
            + "Reviewing an application that isn't currently PENDING is a 409.")
    @ApiResponse(responseCode = "200", description = "Review decision applied. Body is "
            + "`{ \"data\": { id, applicantUserId, entityType, justification, status, createdAt, reviewedAt }, "
            + "\"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed summary string "
            + "(\"validation failed\") — the actual failure is in the `errors` array, one entry per field with "
            + "`field` and `message`. `decision` (APPROVE or REJECT) is required.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but not an admin, or its email address "
            + "is not yet verified — both surface as a plain 403 with no way to distinguish them from the "
            + "response's status code alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Application not found.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Either the application has already been reviewed (is no longer PENDING), or "
            + "this is an APPROVE decision and the applicant already holds the EXPERT_REVIEWER role for this "
            + "application's entityType (e.g. it was granted separately by an admin while the application was "
            + "still pending) — both cases are indistinguishable from the response's status code alone; read "
            + "`detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/applications/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<ApplicationResponse> review(@PathVariable UUID id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        ApplicationResponse data = expertReviewerService.review(currentUserId(), id, request);
        String message = messageSource.getMessage("success.expertReviewerReviewed", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

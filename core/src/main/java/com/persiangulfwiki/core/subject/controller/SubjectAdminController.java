package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.subject.service.SubjectDeletionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Admin-only subject operations, under /api/admin like the field vocabulary's. Documented under the
// Subjects tag, whose description lives on SubjectController.
@RestController
@RequestMapping("/api/admin/subjects")
@RequiredArgsConstructor
@Tag(name = "Subjects")
@SecurityRequirement(name = "cookieAuth")
public class SubjectAdminController {

    private final SubjectDeletionService subjectDeletionService;

    @Operation(summary = "Delete a subject permanently", description = "Admin only. Erases the subject with its names, "
            + "aliases, facts, revision history and expert verifications; this cannot be undone. Works on a deleted or "
            + "merged-away subject too (a merged-away id then stops redirecting). Not allowed while any article is "
            + "about the subject, or while another subject's value points at it -- edit those subjects first. Links to "
            + "it in article text don't block it; they show as plain text afterwards. Every permanent delete is "
            + "recorded in the audit log. To hide a subject reversibly, use `DELETE /api/subjects/{subjectId}`.")
    @ApiResponse(responseCode = "204", description = "The subject was deleted permanently.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "An article is about the subject (code SUBJECT_HAS_ARTICLES), or "
            + "another subject's value points at it (code SUBJECT_REFERENCED).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID subjectId) {
        subjectDeletionService.hardDelete(currentUserId(), subjectId);
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}

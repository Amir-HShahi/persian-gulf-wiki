package com.persiangulfwiki.core.admin.controller;

import com.persiangulfwiki.core.admin.dto.AdminUserResponse;
import com.persiangulfwiki.core.admin.dto.RoleGrantRequest;
import com.persiangulfwiki.core.admin.dto.UserStatusRequest;
import com.persiangulfwiki.core.admin.service.AdminService;
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
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Admin-only user management: listing users, granting/revoking roles, and suspend/ban/reinstate")
@SecurityRequirement(name = "cookieAuth")
public class AdminController {

    private final AdminService adminService;
    private final MessageSource messageSource;

    @Operation(summary = "List users", description = "Admin only. Simple page/size paging, newest-registered ordering left to the "
            + "database's default id order (no explicit sort requested by this step).")
    @ApiResponse(responseCode = "200", description = "Page of users. Body is "
            + "`{ \"data\": [ { id, username, email, enabled, emailVerified, roles, createdAt }, ... ], \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but not an admin, or its email address "
            + "is not yet verified — both surface as a plain 403 with no way to distinguish them from the "
            + "response's status code alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "`page` is negative or `size` is less than 1.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "page", description = "Zero-based page index. Must be 0 or greater.")
    @Parameter(name = "size", description = "Page size. Must be 1 or greater.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<AdminUserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AdminUserResponse> data = adminService.listUsers(page, size);
        String message = messageSource.getMessage("success.adminUsersList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Grant or revoke a role", description = "Admin only. Granting a (role, entityType) pair the user already holds is a "
            + "409 conflict; revoking one they don't hold is a 404. Revoking the system's last "
            + "ADMIN is rejected with a 409, since nothing could grant the next one back. Every "
            + "grant/revoke of ADMIN itself is additionally logged at WARN level, on top of the "
            + "normal audit-log entry, since it's the highest-trust action in the system.")
    @ApiResponse(responseCode = "200", description = "Role grant/revoke applied. Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed summary string "
            + "(\"validation failed\") — the actual failures are in the `errors` array, one entry per field with "
            + "`field` and `message`. `role` and `action` are required; `entityType` is free-form and only "
            + "meaningful for an EXPERT_REVIEWER role — leave it null for a global role such as MODERATOR or "
            + "ADMIN.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but not an admin, or its email address "
            + "is not yet verified — both surface as a plain 403 with no way to distinguish them from the "
            + "response's status code alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Target user not found, or (on revoke) the user doesn't hold that role.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Role already granted, or this revoke would leave zero ADMIN users.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    // Revoking a role (including ADMIN itself) only stops *new* tokens from carrying it: an
    // access token issued before the revoke keeps working until it expires (up to
    // accessTokenTtlMinutes), since JwtAuthenticationFilter is stateless and doesn't re-check
    // the DB per request — same accepted latency window as AuthService.login's disabled-user
    // check.
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> updateRole(@PathVariable UUID id, @Valid @RequestBody RoleGrantRequest request) {
        adminService.grantOrRevokeRole(currentUserId(), id, request);
        String message = messageSource.getMessage("success.adminRoleUpdated", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    @Operation(summary = "Suspend, ban, or reinstate a user", description = "Admin only. This codebase's `users` table only has a boolean `enabled` column "
            + "today — SUSPEND and BAN are administratively distinct but both persist as "
            + "enabled=false; REINSTATE sets enabled=true. Which of SUSPEND/BAN was requested "
            + "is preserved only in the audit log, not as separate persisted state.")
    @ApiResponse(responseCode = "200", description = "Status updated. Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed summary string "
            + "(\"validation failed\") — the actual failure is in the `errors` array, one entry per field with "
            + "`field` and `message`. `action` (SUSPEND, BAN, or REINSTATE) is required.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is authenticated but not an admin, or its email address "
            + "is not yet verified — both surface as a plain 403 with no way to distinguish them from the "
            + "response's status code alone; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Target user not found.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/users/{id}/status")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> updateStatus(@PathVariable UUID id, @Valid @RequestBody UserStatusRequest request) {
        adminService.updateStatus(currentUserId(), id, request);
        String message = messageSource.getMessage("success.adminStatusUpdated", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

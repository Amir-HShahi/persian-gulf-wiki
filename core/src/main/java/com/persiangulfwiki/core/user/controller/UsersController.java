package com.persiangulfwiki.core.user.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.password.service.PasswordService;
import com.persiangulfwiki.core.user.dto.ChangePasswordRequest;
import com.persiangulfwiki.core.user.dto.SessionResponse;
import com.persiangulfwiki.core.user.dto.UserProfile;
import com.persiangulfwiki.core.user.dto.UserResponse;
import com.persiangulfwiki.core.user.service.UserService;

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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "The authenticated user's own profile, active sessions, and password")
@SecurityRequirement(name = "cookieAuth")
public class UsersController {

    private final UserService userService;
    private final PasswordService passwordService;
    private final MessageSource messageSource;

    @Value("${app.jwt.access-token-cookie-name}")
    private final String accessTokenCookieName;

    @Value("${app.jwt.refresh-token-cookie-name}")
    private final String refreshTokenCookieName;

    @Operation(summary = "Get the current user's profile", description = "Reads the user id from the access-token cookie's authenticated principal; there is "
            + "no path parameter, so this can only ever return the caller's own profile.")
    @ApiResponse(responseCode = "200", description = "Profile of the authenticated user. Body is "
            + "`{ \"data\": { id, username, email, roles }, \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The access token is valid but the user it names no longer exists (e.g. the "
            + "account was deleted after the token was issued, but before it expired).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<UserResponse> me() {
        UserProfile profile = userService.getProfile(currentUserId());
        UserResponse data = new UserResponse(
                profile.user().getId(),
                profile.user().getUsername(),
                profile.user().getEmail(),
                profile.roles());
        String message = messageSource.getMessage("success.userProfile", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List the current user's active sessions", description = "Returns every refresh token for the authenticated user that is neither "
            + "revoked nor expired, newest first — one entry per device/browser currently able "
            + "to refresh a session.")
    @ApiResponse(responseCode = "200", description = "Active sessions for the authenticated user (empty list if none). Body is "
            + "`{ \"data\": [ { id, deviceLabel, ipAddress, createdAt, expiresAt }, ... ], \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The authenticated account's email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me/sessions")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SessionResponse>> sessions() {
        List<SessionResponse> data = userService.listActiveSessions(currentUserId());
        String message = messageSource.getMessage("success.userSessionsList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Revoke one of the current user's sessions", description = "Marks the named refresh token revoked, ending that session (that device/browser "
            + "can no longer refresh; it is not force-logged-out until its access token also "
            + "expires). Can only target the caller's own sessions — a session id that exists "
            + "but belongs to another user is reported identically to one that doesn't exist "
            + "at all, to avoid leaking other users' session ids via a status-code oracle.")
    @ApiResponse(responseCode = "200", description = "Session revoked. Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The authenticated account's email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No session with that id belongs to the authenticated user — either it never "
            + "existed or it belongs to someone else.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "That session was already revoked (e.g. a duplicate revoke request, or the "
            + "session already expired and was revoked by another flow).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @DeleteMapping("/me/sessions/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> revokeSession(@PathVariable UUID id) {
        userService.revokeSession(currentUserId(), id);
        String message = messageSource.getMessage("success.sessionRevoked", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    @Operation(summary = "Change the current user's password", description = "Requires the current password to be re-confirmed. On success, revokes every "
            + "active refresh token for the user — including the one behind this very request — "
            + "and clears this response's access/refresh cookies, so the frontend must force a "
            + "fresh login afterward.")
    @ApiResponse(responseCode = "200", description = "Password changed; access and refresh cookies cleared. Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed "
            + "summary string (\"validation failed\") — the actual failures are in the `errors` array, one "
            + "entry per field with `field` and `message`. For `newPassword`, `message` enumerates every "
            + "unmet rule at once: at least 8 characters, at most 72 characters, at least one uppercase "
            + "letter, one lowercase letter, one digit, and one special character (one of "
            + "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\). `currentPassword` is only required to be "
            + "non-blank — it's checked against the account's existing password, not the complexity "
            + "rules, since those only apply when setting a new password.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Either the access token cookie is missing, invalid, or expired, or it is "
            + "valid but `currentPassword` doesn't match the account's password — both surface as "
            + "a plain 401 with no way to distinguish them from the response alone.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The authenticated account's email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The access token is valid but the user it names no longer exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PostMapping("/me/change-password")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            HttpServletResponse response) {
        passwordService.changePassword(currentUserId(), request.currentPassword(), request.newPassword());

        // Same posture as PasswordService.changePassword revoking every refresh token:
        // the current session's cookies die too, forcing the frontend to re-login rather
        // than silently continuing on a stale access token.
        response.addHeader(HttpHeaders.SET_COOKIE, buildExpiredCookie(accessTokenCookieName).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildExpiredCookie(refreshTokenCookieName).toString());

        String message = messageSource.getMessage("success.changePassword", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    @Operation(summary = "Unlink Google sign-in from the current account", description = "Always safe to call: every account is guaranteed to have a usable password by "
            + "the time it can reach this endpoint at all (a Google-only account can't "
            + "authenticate anywhere but /api/auth/oauth2/complete-registration until it sets "
            + "one), so unlinking can never leave the account with zero login paths. A "
            + "notification email is sent best-effort to the account's address either way.")
    @ApiResponse(responseCode = "200", description = "Google unlinked (or was already unlinked — idempotent). Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The authenticated account's email address is not yet verified.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The access token is valid but the user it names no longer exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @DeleteMapping("/me/oauth/google")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> unlinkGoogle() {
        userService.unlinkGoogle(currentUserId());
        String message = messageSource.getMessage("success.googleUnlinked", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    private ResponseCookie buildExpiredCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .sameSite("None")
                .maxAge(Duration.ZERO)
                .build();
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

package com.persiangulfwiki.core.password.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.password.dto.ForgotPasswordRequest;
import com.persiangulfwiki.core.password.dto.ResetPasswordRequest;
import com.persiangulfwiki.core.password.service.PasswordService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/password")
@Tag(name = "Password Management", description = "Forgot-password and reset-password flow (email-issued token, not a login session)")
public class PasswordController {

    private final PasswordService passwordService;
    private final MessageSource messageSource;

    @Operation(summary = "Request a password reset email", description = "Unlike login, this endpoint does NOT mask whether the email is registered — "
            + "a non-existent email returns a 404 rather than a uniform 200, since the "
            + "reset-token flow has no equivalent user-enumeration mitigation in place today. "
            + "On success, a single-use reset token is generated and emailed best-effort: "
            + "sendPasswordResetEmail is @Async, so this response does not confirm delivery, "
            + "only that the token was persisted.")
    @ApiResponse(responseCode = "200", description = "Reset token issued and persisted; email dispatch was triggered (not confirmed "
            + "delivered). Body is `{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed "
            + "summary string (\"validation failed\") — the actual failures are in the `errors` array, one "
            + "entry per field with `field` and `message`. `email` is required and must be a "
            + "valid email address.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No user is registered with the given email.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordService.forgotPassword(request.email());
        String message = messageSource.getMessage("success.forgotPassword", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    @Operation(summary = "Reset a password using an emailed token", description = "The token is single-use and time-limited (app.password.reset-token-ttl-minutes): "
            + "a token that's already been consumed or has expired is rejected the same way as "
            + "an unrecognized one. On success, every existing session for the user is revoked "
            + "— including the one, if any, that the caller is currently using — so a stolen "
            + "refresh cookie can't outlive the credential that issued it.")
    @ApiResponse(responseCode = "200", description = "Password changed; all of the user's active sessions revoked. Body is "
            + "`{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed "
            + "summary string (\"validation failed\") — the actual failures are in the `errors` array, one "
            + "entry per field with `field` and `message`. For `newPassword`, `message` enumerates every "
            + "unmet rule at once: at least 8 characters, at most 72 characters, at least one uppercase "
            + "letter, one lowercase letter, one digit, and one special character (one of "
            + "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\). `token` is only required to be non-blank.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Token is unrecognized, already used, or expired — all three cases return "
            + "the same generic \"invalid password reset token\" detail, by design, so a client "
            + "can't distinguish an expired token from a guessed one.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.resetPassword(request.token(), request.newPassword());
        String message = messageSource.getMessage("success.resetPassword", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }
}

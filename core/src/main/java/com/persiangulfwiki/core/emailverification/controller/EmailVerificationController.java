package com.persiangulfwiki.core.emailverification.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.emailverification.dto.VerifyEmailRequest;
import com.persiangulfwiki.core.emailverification.service.EmailVerificationService;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/email-verification")
@Tag(name = "Email Verification", description = "Email address verification via emailed single-use token")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;
    private final MessageSource messageSource;

    @Operation(summary = "Verify an email address using an emailed token", description = "The token is single-use and time-limited "
            + "(app.email-verification.token-ttl-minutes): unlike a login session, replaying an "
            + "already-consumed or expired token must hard-fail rather than silently succeed a "
            + "second time. Unauthenticated: the caller is identified entirely by the token, not "
            + "by a session cookie.")
    @ApiResponse(responseCode = "200", description = "Email verified: the token's owning user is marked emailVerified, and the token "
            + "is consumed. Body is `{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed "
            + "summary string (\"validation failed\") — the actual failures are in the `errors` array, one "
            + "entry per field with `field` and `message`. `token` is required.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Token is unrecognized, already used, or expired — all three cases return "
            + "the same generic \"invalid email verification token\" detail, by design, so a client "
            + "can't distinguish an expired token from a guessed one.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> verify(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verifyEmail(request.token());
        String message = messageSource.getMessage("success.emailVerified", null, LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    @Operation(summary = "Resend the email verification link", description = "Issues a new single-use verification token for the "
            + "authenticated user and emails it best-effort: sendVerificationEmail is @Async, so "
            + "this response confirms the token was persisted, not that the email was delivered — "
            + "delivery failures are logged server-side and not surfaced to the caller. Does not "
            + "invalidate any previously issued, still-valid verification token.")
    @ApiResponse(responseCode = "200", description = "New verification token issued and persisted; email dispatch was triggered "
            + "(not confirmed delivered). Body is `{ \"data\": null, \"message\": string }`.")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated (missing/invalid/expired session cookie)", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The authenticated user id no longer resolves to a user (e.g. the account "
            + "was deleted after the session cookie was issued).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN "
                    + "cookie, then encode its value and send the encoded result in this header "
                    + "— see the API description above for the required encoding algorithm; "
                    + "sending the raw cookie value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> resend() {
        emailVerificationService.sendVerification(currentUserId());
        String message = messageSource.getMessage("success.emailVerificationResend", null,
                LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

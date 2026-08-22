package com.persiangulfwiki.core.oauth2.controller;

import com.persiangulfwiki.core.auth.dto.LoginResult;
import com.persiangulfwiki.core.auth.service.AuthService;
import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.oauth2.dto.CompleteOAuthRegistrationRequest;
import com.persiangulfwiki.core.oauth2.service.OAuth2Service;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.utils.CookieUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/oauth2")
@Tag(name = "OAuth2", description = "Signing in or registering with a Google account: starting the redirect "
        + "at GET /oauth2/authorization/google, Google's callback to GET /login/oauth2/code/google, and — for "
        + "brand-new accounts — setting a password here to graduate a pending Google-only account into a "
        + "normal session")
public class OAuth2Controller {

    private final OAuth2Service oAuth2Service;
    private final AuthService authService;
    private final MessageSource messageSource;

    @Value("${app.jwt.access-token-cookie-name}")
    private final String accessTokenCookieName;

    @Value("${app.jwt.refresh-token-cookie-name}")
    private final String refreshTokenCookieName;

    @Operation(summary = "Set a username and password for a pending Google OAuth2 account", description = "Consumes the pending-password-setup cookie left by a brand-new Google signup that "
            + "has no username or password yet — the caller's identity comes from that cookie, not "
            + "from a token in the request body. On success, the account's username and password are "
            + "set for the first time and the pending cookie is replaced with a normal access/refresh "
            + "session pair, identical in shape to login's — from this point on the account behaves "
            + "as a regular account with no special pending status.")
    @ApiResponse(responseCode = "200", description = "Username and password set; pending cookie replaced with a real access/refresh "
            + "session. Body is `{ \"data\": null, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are set: `access_token` (short-lived) and `refresh_token` "
            + "(long-lived), identical in shape to login's.", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed "
            + "summary string (\"validation failed\") — the actual failures are in the `errors` array, one "
            + "entry per field with `field` and `message`. For `password`, `message` enumerates every "
            + "unmet rule at once: at least 8 characters, at most 72 characters, at least one uppercase "
            + "letter, one lowercase letter, one digit, and one special character (one of "
            + "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\). For `username`, `message` reports that `username` "
            + "must be 3–50 characters and contain only letters, digits, underscores, and hyphens.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "No valid pending or authenticated session cookie at all.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The session cookie is valid but the user it names no longer exists (e.g. the "
            + "account was deleted after the cookie was issued).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Two distinct causes share this status code. The account already has a password "
            + "set (`detail`: \"password already set for this account\") — either it was already completed "
            + "once, or this endpoint was called with a normal (non-pending) session cookie for an account "
            + "that never needed password setup in the first place. Or the requested username is already "
            + "taken by another account (`detail`: \"username already in use\") — either detected by an "
            + "upfront existence check or, on a check-then-insert race, by a unique-constraint violation on "
            + "save.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @SecurityRequirement(name = "cookieAuth")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie value "
            + "here is rejected. This endpoint is cookie-authenticated, so unlike register/login it is "
            + "NOT exempt from CSRF protection.")
    @PostMapping("/complete-registration")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> completeRegistration(
            @Valid @RequestBody CompleteOAuthRegistrationRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = UUID.fromString(authentication.getName());

        User user = oAuth2Service.completeRegistration(userId, request.username(), request.password());

        LoginResult result = authService.issueSessionFor(user, httpRequest);

        ResponseCookie accessCookie = CookieUtils.build(accessTokenCookieName, result.accessToken(),
                Duration.ofMinutes(result.accessTokenTtlMinutes()));
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        ResponseCookie refreshCookie = CookieUtils.build(refreshTokenCookieName, result.refreshToken(),
                Duration.ofDays(result.refreshTokenTtlDays()));
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        String message = messageSource.getMessage("success.oauth2CompleteRegistration", null,
                LocaleContextHolder.getLocale());
        return ApiResult.ofMessage(message);
    }
}

package com.persiangulfwiki.core.auth.controller;

import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.LoginResult;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.auth.dto.RegisterResponse;
import com.persiangulfwiki.core.auth.exception.InvalidRefreshTokenException;
import com.persiangulfwiki.core.auth.service.AuthService;
import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.emailverification.service.EmailVerificationService;
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
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Registration, login, session refresh/logout, and CSRF token issuance")
public class AuthController {

        private final AuthService authService;
        private final EmailVerificationService emailVerificationService;
        private final MessageSource messageSource;

        @Value("${app.jwt.access-token-cookie-name}")
        private final String accessTokenCookieName;

        @Value("${app.jwt.refresh-token-cookie-name}")
        private final String refreshTokenCookieName;

        @Operation(summary = "Issue a CSRF token", description = "Calling this endpoint is what causes the XSRF-TOKEN cookie to actually be set on "
                        +
                        "the response. Call this before any state-changing request other than " +
                        "register/login/forgot-password/reset-password/verify-email, then encode the XSRF-TOKEN " +
                        "cookie's value and send the encoded result as the X-XSRF-TOKEN header on that request " +
                        "— see the API description above for the required encoding algorithm; sending the raw " +
                        "cookie value is rejected.")
        @ApiResponse(responseCode = "204", description = "XSRF-TOKEN cookie set on the response")
        @GetMapping("/csrf")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void csrf(CsrfToken csrfToken) {
                // CsrfFilter defers token resolution until something reads getToken();
                // only that read triggers CookieCsrfTokenRepository.saveToken() to
                // actually write the XSRF-TOKEN cookie.
                csrfToken.getToken();
        }

        @Operation(summary = "Register a new user", description = "Creates the user with the CONTRIBUTOR role and, best-effort, triggers a "
                        +
                        "verification email. A failure sending that email is logged and does NOT fail the " +
                        "request — a 201 here does not guarantee a verification email was actually sent.")
        @ApiResponse(responseCode = "201", description = "User created; access and refresh cookies set (auto-login on signup, same cookies "
                        +
                        "as login). Body is `{ \"data\": { id, username, email }, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are set: `access_token` (short-lived) and `refresh_token` "
                        +
                        "(long-lived), identical in shape to login's. The freshly-registered account is "
                        +
                        "unverified, so its access token carries a `verified: false` claim — most endpoints "
                        +
                        "will reject it with 403 EMAIL_NOT_VERIFIED until the account is verified.", schema = @Schema(type = "string")))
        @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed " +
                        "summary string (\"validation failed\") — the actual failures are in the `errors` array, " +
                        "one entry per field with `field` and `message`. For `password`, `message` enumerates " +
                        "every unmet rule at once: at least 8 characters, at most 72 characters, at least one " +
                        "uppercase letter, one lowercase letter, one digit, and one special character (one of " +
                        "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\). `username` and `email` have their own " +
                        "required-format and length rules that can also fail here.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @ApiResponse(responseCode = "409", description = "Email or username already in use — either detected by an upfront existence "
                        +
                        "check (`detail`: \"email already in use\" / \"username already in use\") or, on a " +
                        "check-then-insert race, by a unique-constraint violation on save (`detail`: " +
                        "\"username or email already in use\").", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @PostMapping("/register")
        @ResponseStatus(HttpStatus.CREATED)
        public ApiResult<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                        HttpServletRequest httpRequest, HttpServletResponse response) {
                User user = authService.register(request);

                // Auto-login on signup: same cookies login would set. The account is unverified,
                // so the access token's `verified` claim is false until email verification.
                LoginResult result = authService.issueSessionFor(user, httpRequest);
                setAuthCookies(response, result);

                // Triggered after register's transaction has committed: a failure here (e.g. a
                // transient DB error persisting the token) must never turn a successful
                // registration into a 500.
                try {
                        emailVerificationService.sendVerification(user.getId());
                } catch (Exception e) {
                        log.error("failed to trigger verification email for user {}", user.getId(), e);
                }

                RegisterResponse data = new RegisterResponse(user.getId(), user.getUsername(), user.getEmail());
                String message = messageSource.getMessage("success.register", null, LocaleContextHolder.getLocale());
                return ApiResult.of(data, message);
        }

        @Operation(summary = "Authenticate (Login) a user", description = "On success, sets the access-token and refresh-token cookies via Set-Cookie.")
        @ApiResponse(responseCode = "200", description = "Authenticated; access and refresh cookies set. Body is "
                        + "`{ \"data\": null, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are set: `access_token` (short-lived) and `refresh_token` "
                        +
                        "(long-lived, used to obtain new access tokens from POST /api/auth/refresh). Both are " +
                        "HttpOnly (not readable from JavaScript), Secure (HTTPS only), SameSite=None, and scoped " +
                        "to path `/`. Each expires after its own lifetime — `refresh_token` outlives " +
                        "`access_token` by design.", schema = @Schema(type = "string")))
        @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is a fixed " +
                        "summary string (\"validation failed\") — the actual failures are in the `errors` array, " +
                        "one entry per field with `field` and `message`. `email` and `password` are both " +
                        "required and `email` must be a valid email address.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @ApiResponse(responseCode = "401", description = "Invalid credentials, or the account is disabled. The invalid-credentials case is "
                        +
                        "returned identically whether the email doesn't exist or the password is wrong — this " +
                        "is deliberate, not a bug, to avoid a user-enumeration oracle on this endpoint. A " +
                        "disabled account returns a distinct message but the same status.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @PostMapping("/login")
        @ResponseStatus(HttpStatus.OK)
        public ApiResult<Void> login(@Valid @RequestBody LoginRequest request,
                        HttpServletRequest httpRequest, HttpServletResponse response) {
                LoginResult result = authService.login(request, httpRequest);
                setAuthCookies(response, result);

                String message = messageSource.getMessage("success.login", null, LocaleContextHolder.getLocale());
                return ApiResult.ofMessage(message);
        }

        @Operation(summary = "Rotate the refresh token and issue new session cookies", description = "Reads the refresh-token cookie, burns it (rotation: the presented token is "
                        +
                        "revoked regardless of outcome, so a stolen-and-replayed token fails on its next use), " +
                        "re-fetches the user's roles from the database (not the old token's claims, so a role " +
                        "change since login is picked up), and issues a new access/refresh cookie pair.")
        @ApiResponse(responseCode = "200", description = "Refreshed; new access and refresh cookies set. Body is "
                        + "`{ \"data\": null, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are set, replacing the previous pair: `access_token` (short-lived) "
                        +
                        "and `refresh_token` (long-lived, single-use — this response's value must be used for " +
                        "the next refresh, since the one just presented is now revoked). Both are HttpOnly " +
                        "(not readable from JavaScript), Secure (HTTPS only), SameSite=None, and scoped to path " +
                        "`/`.", schema = @Schema(type = "string")))
        @ApiResponse(responseCode = "401", description = "Refresh token cookie missing, unrecognized, already revoked, or expired — or the "
                        +
                        "account it belongs to has since been disabled.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
                        +
                        "then encode its value and send the encoded result in this header — see the API "
                        +
                        "description above for the required encoding algorithm; sending the raw cookie value "
                        +
                        "here is rejected.")
        @PostMapping("/refresh")
        @ResponseStatus(HttpStatus.OK)
        public ApiResult<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
                String rawRefreshToken = CookieUtils.read(request, refreshTokenCookieName);
                if (rawRefreshToken == null) {
                        throw new InvalidRefreshTokenException();
                }

                LoginResult result = authService.refresh(rawRefreshToken, request);
                setAuthCookies(response, result);

                String message = messageSource.getMessage("success.refresh", null, LocaleContextHolder.getLocale());
                return ApiResult.ofMessage(message);
        }

        @Operation(summary = "Log out the current session", description = "Revokes the refresh token identified by the refresh-token cookie and clears "
                        +
                        "both auth cookies. Idempotent by design: a missing cookie, an already-revoked token, " +
                        "or a token that doesn't match any session is not an error — logout always succeeds.")
        @ApiResponse(responseCode = "200", description = "Logged out; access and refresh cookies cleared. Body is "
                        + "`{ \"data\": null, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are cleared: `access_token` and `refresh_token`, each sent back "
                        +
                        "with an empty value and Max-Age 0 so the browser deletes them. Uses the same " +
                        "HttpOnly/Secure/SameSite=None/path `/` attributes as when they were set, which is " +
                        "required for the browser to recognize this as clearing the same cookie.", schema = @Schema(type = "string")))
        @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
                        +
                        "then encode its value and send the encoded result in this header — see the API "
                        +
                        "description above for the required encoding algorithm; sending the raw cookie value "
                        +
                        "here is rejected.")
        @PostMapping("/logout")
        @ResponseStatus(HttpStatus.OK)
        public ApiResult<Void> logout(HttpServletRequest request, HttpServletResponse response) {
                String rawRefreshToken = CookieUtils.read(request, refreshTokenCookieName);
                if (rawRefreshToken != null) {
                        authService.logout(rawRefreshToken);
                }

                response.addHeader(HttpHeaders.SET_COOKIE,
                                buildCookie(accessTokenCookieName, "", Duration.ZERO).toString());
                response.addHeader(HttpHeaders.SET_COOKIE,
                                buildCookie(refreshTokenCookieName, "", Duration.ZERO).toString());

                String message = messageSource.getMessage("success.logout", null, LocaleContextHolder.getLocale());
                return ApiResult.ofMessage(message);
        }

        @Operation(summary = "Log out every session for the current user", description = "Revokes all active refresh tokens for the authenticated user (all devices, "
                        +
                        "not just the current one) and clears this response's access/refresh cookies.")
        @ApiResponse(responseCode = "200", description = "All sessions revoked; access and refresh cookies cleared. Body is "
                        + "`{ \"data\": null, \"message\": string }`.", headers = @Header(name = "Set-Cookie", description = "Two cookies are cleared: `access_token` and `refresh_token`, each sent back "
                        +
                        "with an empty value and Max-Age 0 so the browser deletes them. Uses the same " +
                        "HttpOnly/Secure/SameSite=None/path `/` attributes as when they were set, which is " +
                        "required for the browser to recognize this as clearing the same cookie.", schema = @Schema(type = "string")))
        @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired. This endpoint requires "
                        +
                        "authentication, unlike the rest of this resource.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
        @SecurityRequirement(name = "cookieAuth")
        @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
                        +
                        "then encode its value and send the encoded result in this header — see the API "
                        +
                        "description above for the required encoding algorithm; sending the raw cookie value "
                        +
                        "here is rejected.")
        @PostMapping("/logout-all")
        @ResponseStatus(HttpStatus.OK)
        public ApiResult<Void> logoutAll(HttpServletResponse response) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                UUID userId = UUID.fromString(authentication.getName());
                authService.logoutAll(userId);

                response.addHeader(HttpHeaders.SET_COOKIE,
                                buildCookie(accessTokenCookieName, "", Duration.ZERO).toString());
                response.addHeader(HttpHeaders.SET_COOKIE,
                                buildCookie(refreshTokenCookieName, "", Duration.ZERO).toString());

                String message = messageSource.getMessage("success.logoutAll", null, LocaleContextHolder.getLocale());
                return ApiResult.ofMessage(message);
        }

        private void setAuthCookies(HttpServletResponse response, LoginResult result) {
                ResponseCookie accessCookie = buildCookie(accessTokenCookieName, result.accessToken(),
                                Duration.ofMinutes(result.accessTokenTtlMinutes()));
                response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

                ResponseCookie refreshCookie = buildCookie(refreshTokenCookieName, result.refreshToken(),
                                Duration.ofDays(result.refreshTokenTtlDays()));
                response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        }

        private ResponseCookie buildCookie(String name, String value, Duration maxAge) {
                return CookieUtils.build(name, value, maxAge);
        }
}

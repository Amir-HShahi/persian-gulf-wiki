package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.auth.dto.LoginResult;
import com.persiangulfwiki.core.auth.service.AuthService;
import com.persiangulfwiki.core.security.JwtService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.utils.CookieUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

// Success handler for Spring Security's default OIDC login flow (Google's registration
// includes the "openid" scope, so the principal here is always an OidcUser, not a plain
// OAuth2User — no custom OidcUserService is needed since no extra claims/authorities
// mapping is required beyond what's used below). This is the branching point that decides
// whether a Google login is a returning user, an auto-link onto an existing password
// account, a retry of an abandoned Google-only signup, or a brand new account — the actual
// case A/B/C/D branching and DB writes live in GoogleOAuth2UserResolver (see its own
// Javadoc for why that logic is NOT inlined here as a @Transactional method on this class).
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final GoogleOAuth2UserResolver userResolver;
    private final AuthService authService;
    private final JwtService jwtService;

    @Value("${app.jwt.access-token-cookie-name}")
    private final String accessTokenCookieName;

    @Value("${app.jwt.refresh-token-cookie-name}")
    private final String refreshTokenCookieName;

    @Value("${app.oauth2.success-redirect-url}")
    private final String successRedirectUrl;

    @Value("${app.oauth2.pending-password-redirect-url}")
    private final String pendingPasswordRedirectUrl;

    @Value("${app.oauth2.failure-redirect-url}")
    private final String failureRedirectUrl;

    @Value("${app.jwt.pending-token-ttl-minutes}")
    private final long pendingTokenTtlMinutes;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
            String googleSub = oidcUser.getSubject();
            // getEmail() is trusted as verified with no explicit email_verified claim check:
            // Google's OIDC responses only ever include an email at all when it's verified
            // (this is a Google-specific guarantee, not a general OIDC one), which is what
            // makes the auto-link decision in GoogleOAuth2UserResolver Case B safe. Do NOT
            // copy this assumption to a future second OAuth provider without re-checking that
            // provider's own verification guarantees — read the email_verified claim
            // explicitly there instead.
            String email = oidcUser.getEmail().toLowerCase(Locale.ROOT);

            GoogleOAuth2LoginOutcome outcome = userResolver.resolve(googleSub, email);

            if (outcome.pending()) {
                issuePendingSessionAndRedirect(outcome.user(), response);
            } else {
                issueRealSessionAndRedirect(outcome.user(), request, response);
            }
        } catch (Exception e) {
            log.error("failed to complete Google OAuth2 login", e);
            response.sendRedirect(failureRedirectUrl);
        }
    }

    private void issueRealSessionAndRedirect(User user, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LoginResult result = authService.issueSessionFor(user, request);

        ResponseCookie accessCookie = CookieUtils.build(accessTokenCookieName, result.accessToken(),
                Duration.ofMinutes(result.accessTokenTtlMinutes()));
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        ResponseCookie refreshCookie = CookieUtils.build(refreshTokenCookieName, result.refreshToken(),
                Duration.ofDays(result.refreshTokenTtlDays()));
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        response.sendRedirect(successRedirectUrl);
    }

    private void issuePendingSessionAndRedirect(User user, HttpServletResponse response) throws IOException {
        String pendingToken = jwtService.generatePendingPasswordSetupToken(user.getId());

        ResponseCookie pendingCookie = CookieUtils.build(accessTokenCookieName, pendingToken,
                Duration.ofMinutes(pendingTokenTtlMinutes));
        response.addHeader(HttpHeaders.SET_COOKIE, pendingCookie.toString());

        response.sendRedirect(pendingPasswordRedirectUrl);
    }
}

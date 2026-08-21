package com.persiangulfwiki.core.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Handles failures Spring's OAuth2 login filter itself classifies as authentication
// failures (denied consent, Google-side error, token exchange failure, etc.) — distinct
// from exceptions thrown inside GoogleOAuth2SuccessHandler, which is already past
// authentication and catches its own failures.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuth2FailureHandler implements AuthenticationFailureHandler {

    @Value("${app.oauth2.failure-redirect-url}")
    private final String failureRedirectUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // Denied consent is routine user behavior, not an application error — keep this
        // terse and never log/redirect with raw request params, since some OAuth error
        // responses echo back attacker-influenced data.
        log.warn("Google OAuth2 login failed: {}", exception.getMessage());
        log.debug("Google OAuth2 login failure detail", exception);
        response.sendRedirect(failureRedirectUrl);
    }
}

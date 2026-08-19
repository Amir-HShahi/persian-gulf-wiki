package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.web.ProblemDetails;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

// Runs after JwtAuthenticationFilter has resolved (or not resolved) authentication.
// Unauthenticated requests are left alone — anyRequest().authenticated() and the 401
// entry point already handle those. Authenticated requests from an unverified account
// are blocked here except for a small allowlist, so unverified users can still complete
// email verification, log out, or refresh their session.
//
// This runs as a Spring Security filter, ahead of the DispatcherServlet, so it can't
// route through GlobalExceptionHandler's @ExceptionHandler methods the way a controller-
// thrown exception would — there's no handler-mapped request yet for @RestControllerAdvice
// to intercept. Instead the body is built as the same ProblemDetail shape
// GlobalExceptionHandler returns for every other error response, plus a `code` property
// (the same mechanism used for the validation handler's `errors` property) so the
// frontend has a machine-readable value to key its redirect off of.
@Component
@RequiredArgsConstructor
public class EmailVerificationRequiredFilter extends OncePerRequestFilter {

    private static final RequestMatcher ALLOWLIST = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher("/api/email-verification/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/logout"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/logout-all"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/csrf"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/users/me"),
            // A pending-password-setup token (see PendingPasswordSetupFilter) carries no
            // `verified` claim at all, even though the underlying Google-verified account
            // is emailVerified — without this, a pending token could never reach the one
            // endpoint PendingPasswordSetupFilter's own allowlist exists to let it reach.
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/oauth2/complete-registration")));

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        Boolean verified = (Boolean) request.getAttribute(JwtAuthenticationFilter.EMAIL_VERIFIED_ATTRIBUTE);
        boolean emailVerified = verified != null && verified;

        if (!emailVerified && !ALLOWLIST.matches(request)) {
            ProblemDetail problemDetail = ProblemDetails.of(
                    HttpStatus.FORBIDDEN, "email verification required", "EMAIL_NOT_VERIFIED", request);

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), problemDetail);
            return;
        }

        filterChain.doFilter(request, response);
    }
}

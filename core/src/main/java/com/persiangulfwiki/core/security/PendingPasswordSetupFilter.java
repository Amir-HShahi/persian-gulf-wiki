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

// A Google OAuth2 signup with no password yet is issued a narrow, short-lived token
// (scope=PENDING_PASSWORD_SETUP, see JwtService#generatePendingPasswordSetupToken) instead
// of a normal session, so it can't touch the rest of the API until it sets a password. This
// filter is that enforcement: it blocks every request carrying a pending-scope token except
// a small allowlist, forcing the client through /api/auth/oauth2/complete-registration first.
//
// It runs before EmailVerificationRequiredFilter for the same reason it exists at all — a
// pending-scope token carries no `verified` claim, so if EmailVerificationRequiredFilter ran
// first it would reject on the wrong grounds (or worse, pass on a stale/default value).
// Normal access tokens carry no `scope` claim at all and pass through here untouched; this
// filter only ever acts on PENDING_PASSWORD_SETUP-scoped requests.
@Component
@RequiredArgsConstructor
public class PendingPasswordSetupFilter extends OncePerRequestFilter {

    private static final String PENDING_PASSWORD_SETUP_SCOPE = "PENDING_PASSWORD_SETUP";

    private static final RequestMatcher ALLOWLIST = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/oauth2/complete-registration"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/csrf"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/logout")));

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String scope = (String) request.getAttribute(JwtAuthenticationFilter.SCOPE_ATTRIBUTE);

        if (!PENDING_PASSWORD_SETUP_SCOPE.equals(scope)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!ALLOWLIST.matches(request)) {
            ProblemDetail problemDetail = ProblemDetails.of(
                    HttpStatus.FORBIDDEN, "password setup required", "PASSWORD_SETUP_REQUIRED", request);

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), problemDetail);
            return;
        }

        filterChain.doFilter(request, response);
    }
}

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
// Public routes (PublicRoutes) are the exception: there the token is dropped and the request
// proceeds as anonymous, since the same request with no cookie at all would be let through.
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
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/logout"),
            // Same reasoning as EmailVerificationRequiredFilter's allowlist: a pending
            // Google signup that the user abandons must still be able to start over on a
            // different email via register/login, which replace the auth cookies wholesale.
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/register"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/auth/login")));

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
            // Same demotion as EmailVerificationRequiredFilter's: a public route reads as
            // anonymous rather than 403, and never as this narrow token's own identity.
            // Clearing the context also means EmailVerificationRequiredFilter, which runs
            // next, sees no authentication and passes the request straight through.
            if (PublicRoutes.ALL.matches(request)) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

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

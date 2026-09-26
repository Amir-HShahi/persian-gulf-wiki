package com.persiangulfwiki.core.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

// The routes that are open to an anonymous caller, shared by SecurityConfig (which permits
// them) and by PendingPasswordSetupFilter/EmailVerificationRequiredFilter (which, on these
// routes, downgrade a restricted session to anonymous instead of answering 403). Keeping one
// definition is what stops the two from drifting: a route permitted here but missing from
// the filters would be readable with no cookie yet 403 with an unverified one.
//
// Deliberately excludes the /api/auth/** permitAll routes. Those act on the caller's own
// session cookies, so each filter's own ALLOWLIST decides them as the caller, not as anonymous.
public final class PublicRoutes {

    // Anonymous GET only -- every other method on these paths requires an account (see
    // SecurityConfig). Scoped by method on purpose: widening to all methods would make the
    // POST routes under the same paths anonymously writable.
    public static final RequestMatcher CONTENT_READS = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/subjects"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/subjects/**"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/sources"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/sources/**"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/articles"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/articles/**")));

    public static final RequestMatcher API_DOCS = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher("/docs/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/v3/api-docs/**")));

    public static final RequestMatcher HEALTH = PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**");

    public static final RequestMatcher ALL = new OrRequestMatcher(List.of(CONTENT_READS, API_DOCS, HEALTH));

    private PublicRoutes() {
    }
}

package com.persiangulfwiki.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.persiangulfwiki.core.utils.CookieUtils;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String EMAIL_VERIFIED_ATTRIBUTE = "emailVerified";
    public static final String SCOPE_ATTRIBUTE = "scope";

    private final JwtService jwtService;

    @Value("${app.jwt.access-token-cookie-name}")
    private final String accessTokenCookieName;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = CookieUtils.read(request, accessTokenCookieName);

        if (token != null) {
            try {
                Claims claims = jwtService.parseClaims(token);
                String userId = claims.getSubject();
                List<?> rawRoles = claims.get("role", List.class);
                List<GrantedAuthority> authorities = rawRoles == null
                        ? List.of()
                        : rawRoles.stream()
                                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                                .toList();

                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));

                // Carried as a request attribute (not the Authentication itself) so
                // EmailVerificationRequiredFilter can gate on it without a DB read, while
                // leaving the principal/authorities shape unchanged for everything else
                // that reads Authentication.
                Boolean verified = claims.get("verified", Boolean.class);
                request.setAttribute(EMAIL_VERIFIED_ATTRIBUTE, verified != null && verified);

                String scope = claims.get("scope", String.class);
                request.setAttribute(SCOPE_ATTRIBUTE, scope);
            } catch (JwtException ignored) {
                // Any malformed/expired/bad-signature token just means "not authenticated" —
                // don't vary the response by exception type, that turns the filter into an
                // oracle.
            }
        }

        filterChain.doFilter(request, response);
    }
}

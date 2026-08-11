package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.oauth2.GoogleOAuth2FailureHandler;
import com.persiangulfwiki.core.oauth2.GoogleOAuth2SuccessHandler;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final PendingPasswordSetupFilter pendingPasswordSetupFilter;
    private final EmailVerificationRequiredFilter emailVerificationRequiredFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler;
    private final GoogleOAuth2FailureHandler googleOAuth2FailureHandler;
    private final ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint;

    @Value("${app.cookie.domain}")
    private String cookieDomain;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Without an explicit Domain, the XSRF-TOKEN cookie is host-only to the API's own
        // hostname (e.g. api.persiangulfwiki.ravensandrunse.me) — frontend JS on a sibling
        // host can never read it via document.cookie to populate X-XSRF-TOKEN, so the
        // double-submit check can never succeed. Widening to the shared registrable parent
        // domain makes it visible to both.
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie.domain(cookieDomain));
        http
                // CORS preflight (OPTIONS) requests carry no cookies and no CSRF token by
                // design — they're sent bare to ask permission before the real request. This
                // must run and let preflight through cleanly before CSRF ever evaluates it,
                // so it goes first in the chain.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .oauth2Login(oauth2 -> oauth2.successHandler(googleOAuth2SuccessHandler)
                        .failureHandler(googleOAuth2FailureHandler))
                // register/login authorize via request-body credentials, not an ambient cookie,
                // so CSRF doesn't apply to them; every other endpoint — including refresh and
                // logout, which are cookie-authenticated — stays protected.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Default request handler (XorCsrfTokenRequestAttributeHandler) is left in
                        // place for BREACH protection. The frontend is responsible for XOR-masking
                        // and base64url-encoding the raw XSRF-TOKEN cookie value itself before
                        // sending it as X-XSRF-TOKEN — see AGENTS.md / frontend CSRF notes for the
                        // exact algorithm it must match.
                        //
                        // Same reasoning as register/login: authorized via request-body data only,
                        // no ambient auth cookie consumed, so CSRF doesn't apply.
                        .ignoringRequestMatchers("/api/auth/register", "/api/auth/login",
                                "/api/password/forgot-password", "/api/password/reset-password",
                                "/api/email-verification/verify"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Boot's error page is an internal forward to /error that re-enters this chain
                        // — without this, it hits anyRequest().authenticated() and turns every error on
                        // a permitAll route into a 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Only these are credential-free or purely cookie-authenticated by design;
                        // logout-all needs a valid SecurityContext (it reads authentication.getName()),
                        // so it must NOT be on this list — it falls through to anyRequest().authenticated().
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/csrf", "/api/password/forgot-password",
                                "/api/password/reset-password", "/api/email-verification/verify").permitAll()
                        // Framework-owned OAuth2 client routes (authorization redirect + callback) —
                        // Spring Security serves these itself, this only exposes them past the
                        // "everything else requires auth" default below.
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Public API docs, not app data.
                        .requestMatchers("/docs/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemDetailAuthenticationEntryPoint))
                // Both default to enabled and would otherwise turn an unauthenticated request
                // into a 302 to a phantom login page instead of a 401.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(pendingPasswordSetupFilter, JwtAuthenticationFilter.class)
                // PendingPasswordSetupFilter must reject a pending-scope request before
                // EmailVerificationRequiredFilter ever runs its own check — a pending-scope
                // token carries no meaningful `verified` claim for that filter to read.
                .addFilterAfter(emailVerificationRequiredFilter, PendingPasswordSetupFilter.class);

        return http.build();
    }
}

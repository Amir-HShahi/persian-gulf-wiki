package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.security.EmailVerificationRequiredFilter;
import com.persiangulfwiki.core.security.JwtAuthenticationFilter;
import com.persiangulfwiki.core.security.PendingPasswordSetupFilter;

import jakarta.servlet.Filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// The second of the two gates protecting /api/dev/** — DevTestUserController's @Profile("dev")
// is the first. An HTTP endpoint needs both: the controller must not exist outside dev, *and*
// something has to let the route past authentication inside dev.
//
// The alternative — adding "/api/dev/**" to SecurityConfig's permitAll list — was rejected
// deliberately. That list is unprofiled, so the permit rule would be live in production. No
// controller would be mapped there, so the result is a 404 rather than a breach, but it leaves
// a standing permit for a route nobody serves, and the next person to add a class under
// com.persiangulfwiki.core.dev has no way to know it is already reachable. Keeping the rule
// here means SecurityConfig's permit list stays a list of genuinely public production routes.
//
// Outside the dev profile this @Configuration is not registered at all, so requests to
// /api/dev/** are not matched by any securityMatcher and fall through to the main chain's
// anyRequest().authenticated() — a 401, before routing ever runs.
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    // Ahead of SecurityConfig.securityFilterChain, which declares no @Order and therefore sits
    // at LOWEST_PRECEDENCE. Spring Security consults chains in order and uses the first whose
    // matcher accepts the request, so this must come first to claim /api/dev/** at all.
    @Bean
    @Order(1)
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Scopes this chain to the dev routes only. Everything else is left to the
                // main chain, which is otherwise fully intact — none of its filters,
                // entry point, or CSRF configuration is affected by this bean.
                .securityMatcher("/api/dev/**")
                // The main chain's CSRF protection covers everything not explicitly ignored.
                // A test harness minting a throwaway user shouldn't have to fetch an
                // XSRF-TOKEN cookie and XOR-mask it (see SecurityConfig's csrf comment) just
                // to make a fixture call, and there is no ambient cookie here to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Both default to enabled and would turn a rejected request into a 302 to a
                // phantom login page — same reasoning as the main chain.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Note what is deliberately absent: JwtAuthenticationFilter,
                // PendingPasswordSetupFilter and EmailVerificationRequiredFilter are not
                // added here, so requests on this chain carry no SecurityContext at all.
                // That is correct for an endpoint whose whole purpose is to be callable
                // before any account exists. Keeping them out takes the three
                // FilterRegistrationBeans below as well — see the comment there.
                .build();
    }

    // Those three filters are @Components, so Boot also auto-registers each one as a
    // plain servlet filter mapped to /*, independently of any SecurityFilterChain. On the
    // main chain that registration never does anything: the chain adds each filter
    // explicitly and early, and OncePerRequestFilter's already-filtered request attribute
    // makes the later container-level invocation a no-op.
    //
    // On this chain nothing adds them, so the container-level copy is the *first*
    // invocation — and it runs after the whole FilterChainProxy, by which point
    // AnonymousAuthenticationFilter has installed an anonymous token that reports
    // isAuthenticated() == true with no email-verified request attribute behind it.
    // EmailVerificationRequiredFilter reads exactly that pair and answers 403
    // EMAIL_NOT_VERIFIED, so every mint call fails. A caller arriving with a real cookie
    // for an unverified account would fail the same way, which is precisely the
    // intermittent failure this endpoint exists to eliminate.
    //
    // Disabling the auto-registration is the documented Boot fix and is inert in
    // production twice over: these beans only exist under dev, and even under dev the main
    // chain still adds all three filters explicitly, so every non-/api/dev route behaves
    // exactly as before.
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        return disableContainerRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<PendingPasswordSetupFilter> pendingPasswordSetupFilterRegistration(
            PendingPasswordSetupFilter filter) {
        return disableContainerRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<EmailVerificationRequiredFilter> emailVerificationRequiredFilterRegistration(
            EmailVerificationRequiredFilter filter) {
        return disableContainerRegistration(filter);
    }

    private <T extends Filter> FilterRegistrationBean<T> disableContainerRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}

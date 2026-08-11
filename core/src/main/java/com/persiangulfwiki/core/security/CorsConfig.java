package com.persiangulfwiki.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        // Explicit origin list, not setAllowedOriginPatterns — cross-origin cookies require
        // credentialed CORS, and browsers reject wildcard origins for those anyway.
        configuration.setAllowedOrigins(origins);
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        // X-XSRF-TOKEN is the CSRF double-submit header (see CookieCsrfTokenRepository in
        // SecurityConfig) — without it here, cross-origin callers would silently fail CSRF
        // checks on /api/auth/refresh, /api/auth/logout, etc. even though CORS itself "works".
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

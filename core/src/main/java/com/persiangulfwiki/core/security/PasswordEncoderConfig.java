package com.persiangulfwiki.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt throws IllegalArgumentException on raw passwords > 72 bytes — validate
        // length in the request DTO so it surfaces as a 400.
        return new BCryptPasswordEncoder();
    }
}

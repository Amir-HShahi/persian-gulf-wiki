package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterTests {

    private static final String PROTECTED_ENDPOINT = "/actuator/health";
    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    void noCookieIsUnauthenticated() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageCookieIsUnauthenticatedNotServerError() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, "not-a-real-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredCookieIsUnauthenticated() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expiredToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", List.of("CONTRIBUTOR"))
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, expiredToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validCookieIsAuthenticated() throws Exception {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), List.of(Role.CONTRIBUTOR), true);

        int responseStatus = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(responseStatus).isNotIn(401, 403);
    }
}

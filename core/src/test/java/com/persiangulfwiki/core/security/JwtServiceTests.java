package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JwtServiceTests {

    @Autowired
    private JwtService jwtService;

    @Test
    void pendingPasswordSetupTokenClaimsRoundTrip() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.generatePendingPasswordSetupToken(userId);
        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("scope", String.class)).isEqualTo("PENDING_PASSWORD_SETUP");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }
}

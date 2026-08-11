package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.user.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration pendingTokenTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
            @Value("${app.jwt.pending-token-ttl-minutes}") long pendingTokenTtlMinutes) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
        this.pendingTokenTtl = Duration.ofMinutes(pendingTokenTtlMinutes);
    }

    public String generateAccessToken(UUID userId, List<Role> roles, boolean emailVerified) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", roles.stream().map(Role::name).toList())
                .claim("verified", emailVerified)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generatePendingPasswordSetupToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("scope", "PENDING_PASSWORD_SETUP")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(pendingTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // Throws jjwt's typed exceptions uncaught (expired/malformed/bad signature) —
    // the caller maps them to "unauthenticated".
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

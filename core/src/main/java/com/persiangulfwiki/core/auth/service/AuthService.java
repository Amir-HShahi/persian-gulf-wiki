package com.persiangulfwiki.core.auth.service;

import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.LoginResult;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.auth.exception.AccountDisabledException;
import com.persiangulfwiki.core.auth.exception.DuplicateUserException;
import com.persiangulfwiki.core.auth.exception.InvalidCredentialsException;
import com.persiangulfwiki.core.auth.exception.InvalidRefreshTokenException;
import com.persiangulfwiki.core.security.JwtService;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.model.RefreshToken;
import com.persiangulfwiki.core.user.repository.RefreshTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;

    @Value("${app.jwt.access-token-ttl-minutes}")
    private final long accessTokenTtlMinutes;

    @Value("${app.jwt.refresh-token-ttl-days}")
    private final long refreshTokenTtlDays;

    @Value("${app.oauth2.abandoned-account-ttl-hours}")
    private final long abandonedAccountTtlHours;

    @Transactional
    public User register(RegisterRequest request) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);

        // A password_hash IS NULL row past the abandoned-account TTL represents a Google
        // signup that was started and never completed, so registering fresh on that email
        // should reclaim it rather than 409 on a row that was never actually a usable
        // account. A non-stale pending row (still within the TTL, i.e. someone might
        // complete it any moment) is deliberately left alone — the existsByEmail check below
        // still fires for that case, since a plain-register attempt racing an in-progress
        // Google signup on the same email is a genuine, if narrow, conflict worth surfacing
        // rather than silently deleting.
        userRepository.findByEmailAndPasswordHashIsNull(normalizedEmail)
                .filter(stale -> stale.getCreatedAt().isBefore(Instant.now().minus(abandonedAccountTtlHours, ChronoUnit.HOURS)))
                .ifPresent(userRepository::delete);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw DuplicateUserException.emailInUse();
        }
        if (userRepository.existsByUsername(request.username())) {
            throw DuplicateUserException.usernameInUse();
        }

        User user = User.builder()
                .username(request.username())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();

        // A DataIntegrityViolationException from the save below (safety net for the
        // check-then-insert race above) propagates to GlobalExceptionHandler as a 409.
        user = userRepository.save(user);
        userRoleRepository.save(UserRole.builder()
                .user(user)
                .role(Role.CONTRIBUTOR)
                .build());

        return user;
    }

    @Transactional
    public LoginResult login(LoginRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        // Null-hash guard: BCryptPasswordEncoder.matches(raw, null) throws
        // IllegalArgumentException rather than returning false. No such rows exist yet
        // (OAuth isn't built), but this closes the NPE/500 risk for free.
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Same exception whether the email doesn't exist or the password is wrong —
            // avoids a user-enumeration oracle.
            throw new InvalidCredentialsException();
        }

        // Distinct exception from a wrong password on purpose: the account owner needs a
        // clear "your account is disabled" message here, not a generic invalid-credentials
        // one. Note this only blocks *new* sessions: an access token issued before
        // suspension keeps working until it expires (up to accessTokenTtlMinutes), since
        // JwtAuthenticationFilter is intentionally stateless and doesn't re-check the DB
        // per request. That window is accepted latency, not a bug.
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }

        return issueSessionFor(user, httpRequest);
    }

    // Shared by register (auto-login on signup) and login: mints an access token plus a
    // fresh refresh token for an already-resolved user. Kept separate from register() so
    // "create the account" and "start a session" stay independently reusable.
    @Transactional
    public LoginResult issueSessionFor(User user, HttpServletRequest httpRequest) {
        List<Role> roles = userRoleRepository.findByUserId(user.getId()).stream()
                .map(UserRole::getRole)
                .toList();

        String accessToken = jwtService.generateAccessToken(user.getId(), roles, user.isEmailVerified());
        String refreshToken = issueRefreshToken(user, httpRequest);
        return new LoginResult(accessToken, accessTokenTtlMinutes, refreshToken, refreshTokenTtlDays);
    }

    @Transactional
    public LoginResult refresh(String rawRefreshToken, HttpServletRequest httpRequest) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (existing.getRevokedAt() != null || existing.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        // Rotation: the presented token is burned here regardless of what happens next,
        // so a stolen-and-replayed token fails on its next use even if this request
        // succeeds for the legitimate caller.
        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);

        User user = existing.getUser();

        // Checked after the revoke above (not before): the presented token belongs to a
        // now-suspended account either way, so it must not survive this call regardless
        // of which branch we take. Distinct exception from a generic invalid-token
        // rejection, same reasoning as login — the caller should be told plainly that the
        // account is disabled rather than getting a token-shaped error. Same
        // accepted-latency note as login applies here too — this only stops *future*
        // rotations; an access token already in hand keeps working until it expires.
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }

        // Re-fetch roles rather than trusting the old token's claims — refresh is the
        // natural point to pick up a role change since the original login. Same reasoning
        // for emailVerified: this is how a verified-but-not-yet-refreshed session's access
        // token self-heals to reflect verification, without needing to force-invalidate it.
        List<Role> roles = userRoleRepository.findByUserId(user.getId()).stream()
                .map(UserRole::getRole)
                .toList();

        String accessToken = jwtService.generateAccessToken(user.getId(), roles, user.isEmailVerified());
        String refreshToken = issueRefreshToken(user, httpRequest);
        return new LoginResult(accessToken, accessTokenTtlMinutes, refreshToken, refreshTokenTtlDays);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .orElse(null);

        // Idempotent by design: an unmatched token (already logged out, garbage cookie,
        // expired) is not an error from the caller's perspective — logout should always
        // succeed.
        if (existing == null || existing.getRevokedAt() != null) {
            return;
        }

        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now());
    }

    private String issueRefreshToken(User user, HttpServletRequest httpRequest) {
        String rawToken = tokenHasher.generateToken();

        // Sliding expiration: each rotation gets a fresh TTL from now, not the
        // original login's fixed expiry.
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS))
                .deviceLabel(httpRequest.getHeader("User-Agent"))
                // Deliberately request.getRemoteAddr(), not X-Forwarded-For: this
                // deployment's proxy topology isn't finalized, and trusting a
                // client-spoofable header here would be worse than not recording IP.
                .ipAddress(httpRequest.getRemoteAddr())
                .build();
        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }
}

package com.persiangulfwiki.core.password.service;

import com.persiangulfwiki.core.auth.exception.InvalidCredentialsException;
import com.persiangulfwiki.core.mail.EmailService;
import com.persiangulfwiki.core.password.exception.InvalidPasswordResetTokenException;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.model.PasswordResetToken;
import com.persiangulfwiki.core.user.repository.PasswordResetTokenRepository;
import com.persiangulfwiki.core.user.repository.RefreshTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHasher tokenHasher;
    private final EmailService emailService;

    @Value("${app.password.reset-token-ttl-minutes}")
    private final long resetTokenTtlMinutes;

    @Transactional
    public void forgotPassword(String email) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UserNotFoundException("no user with that email found"));

        String rawToken = tokenHasher.generateToken();
        String tokenHash = tokenHasher.hash(rawToken);

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(resetTokenTtlMinutes, ChronoUnit.MINUTES))
                .build();
        passwordResetTokenRepository.save(resetToken);

        // Fire-and-forget: sendPasswordResetEmail is @Async, so this returns immediately
        // and failures are logged inside EmailService rather than surfaced here.
        emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
        log.info("password reset token issued for user {}", user.getId());
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(InvalidPasswordResetTokenException::new);

        // Single-use, not idempotent: unlike logout, replaying an already-consumed reset
        // token must hard-fail rather than silently succeed a second time.
        if (resetToken.getUsedAt() != null || resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidPasswordResetTokenException();
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);

        // A password reset invalidates every existing session, not just the current one —
        // otherwise a stolen refresh cookie would outlive the credential that issued it.
        refreshTokenRepository.revokeAllActiveForUser(user.getId(), Instant.now());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + userId));

        // Same exception as a wrong-password login attempt: a mismatched current password
        // here is functionally the same failure mode, not a validation error.
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Same posture as resetPassword: every existing session dies, including the one
        // that made this request — the caller's frontend is expected to force a fresh login.
        refreshTokenRepository.revokeAllActiveForUser(user.getId(), Instant.now());
    }
}

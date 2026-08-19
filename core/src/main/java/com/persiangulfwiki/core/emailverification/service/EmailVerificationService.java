package com.persiangulfwiki.core.emailverification.service;

import com.persiangulfwiki.core.emailverification.exception.InvalidEmailVerificationTokenException;
import com.persiangulfwiki.core.mail.EmailService;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.model.EmailVerificationToken;
import com.persiangulfwiki.core.user.repository.EmailVerificationTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final TokenHasher tokenHasher;
    private final EmailService emailService;

    @Value("${app.email-verification.token-ttl-minutes}")
    private final long verificationTokenTtlMinutes;

    @Transactional
    public void sendVerification(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + userId));

        String rawToken = tokenHasher.generateToken();
        String tokenHash = tokenHasher.hash(rawToken);

        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(verificationTokenTtlMinutes, ChronoUnit.MINUTES))
                .build();
        emailVerificationTokenRepository.save(verificationToken);

        // Fire-and-forget: sendVerificationEmail is @Async, so this returns immediately
        // and failures are logged inside EmailService rather than surfaced here.
        emailService.sendVerificationEmail(user.getEmail(), rawToken);
        log.info("email verification token issued for user {}", user.getId());
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository
                .findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(InvalidEmailVerificationTokenException::new);

        // Single-use, not idempotent: unlike logout, replaying an already-consumed
        // verification token must hard-fail rather than silently succeed a second time.
        if (verificationToken.getUsedAt() != null || verificationToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidEmailVerificationTokenException();
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        verificationToken.setUsedAt(Instant.now());
        emailVerificationTokenRepository.save(verificationToken);
    }
}

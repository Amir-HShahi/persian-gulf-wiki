package com.persiangulfwiki.core.oauth2.service;

import com.persiangulfwiki.core.auth.exception.DuplicateUserException;
import com.persiangulfwiki.core.oauth2.exception.PasswordAlreadySetException;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2Service {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User completeRegistration(UUID userId, String username, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + userId));

        // Defense-in-depth: PendingPasswordSetupFilter already keeps a completed account
        // from reaching this endpoint in normal operation (a real session carries no
        // `scope` claim), but this method shouldn't silently trust an invariant enforced
        // by a filter it doesn't control.
        if (user.getPasswordHash() != null) {
            throw new PasswordAlreadySetException();
        }

        // Same check-then-insert-race pattern as AuthService.register: an upfront existence
        // check for a fast, precise 409, backed by the DB's own unique constraint (via
        // GlobalExceptionHandler's DataIntegrityViolationException handler) as the safety
        // net if two completions race on the same username concurrently. This is an UPDATE,
        // not an INSERT — unlike register's fresh row, this user row already exists — but
        // the same unique-constraint-violation path still applies to an UPDATE that
        // collides with another row's username.
        if (userRepository.existsByUsername(username)) {
            throw DuplicateUserException.usernameInUse();
        }

        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user = userRepository.save(user);
        log.info("username and password set via Google OAuth for user {}", user.getId());
        return user;
    }
}

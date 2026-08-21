package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.mail.EmailService;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Holds the DB-mutating half of the Google login branching (see GoogleOAuth2SuccessHandler
// for the case A/B/C/D writeup) as its own bean so it gets its own transactional proxy.
// GoogleOAuth2SuccessHandler.onAuthenticationSuccess itself is NOT @Transactional: if it
// were, and it also caught exceptions thrown by this resolver, a failure from a
// Spring-Data-JPA-proxied save() (itself @Transactional, REQUIRED) would mark the shared
// transaction rollback-only before the exception reached the catch block — swallowing it
// there would make the outer @Transactional method return normally, and its own proxy
// would then try to commit a rollback-only transaction, throwing UnexpectedRollbackException
// past the catch and past the redirect. Keeping @Transactional only on this resolver, called
// from a non-transactional caller, means a failure here rolls back cleanly and simply
// propagates as the original exception for the caller's try/catch to handle.
@Slf4j
@Component
@RequiredArgsConstructor
class GoogleOAuth2UserResolver {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final EmailService emailService;

    @Transactional
    GoogleOAuth2LoginOutcome resolve(String googleSub, String email) {
        User existingByGoogleSub = userRepository.findByGoogleSub(googleSub).orElse(null);
        User existingByEmail = userRepository.findByEmail(email).orElse(null);

        if (existingByGoogleSub != null) {
            // Case A — returning Google user. This row is guaranteed to have a non-null
            // password_hash: it could only reach this state by having completed
            // complete-registration, or by having been auto-linked onto a pre-existing
            // password account (Case B).
            return new GoogleOAuth2LoginOutcome(existingByGoogleSub, false);
        } else if (existingByEmail != null && existingByEmail.getPasswordHash() != null) {
            // Case B — auto-link. Google verifies the email behind its OAuth identity, so
            // it's safe to link without a manual "confirm this is you" step. Notify the
            // account by email since this silently changes its login surface.
            existingByEmail.setGoogleSub(googleSub);
            userRepository.save(existingByEmail);

            try {
                emailService.sendGoogleAccountLinkedEmail(email);
            } catch (Exception e) {
                log.error("failed to trigger Google-account-linked email for user {}", existingByEmail.getId(), e);
            }

            return new GoogleOAuth2LoginOutcome(existingByEmail, false);
        } else if (existingByEmail != null) {
            // Case C — retry of an abandoned/incomplete Google signup (password_hash is
            // still null). Same person retrying, not a new signup: don't create a duplicate
            // row (would violate the unique email constraint anyway) and don't send a
            // linked-account notification since nothing new was linked.
            if (existingByEmail.getGoogleSub() == null) {
                existingByEmail.setGoogleSub(googleSub);
                userRepository.save(existingByEmail);
            }

            return new GoogleOAuth2LoginOutcome(existingByEmail, true);
        } else {
            // Case D — brand new email, no match at all. Google already verified this
            // email, so there's no separate email-verification round-trip needed.
            User user = User.builder()
                    .email(email)
                    .googleSub(googleSub)
                    .passwordHash(null)
                    .emailVerified(true)
                    .build();
            user = userRepository.save(user);
            userRoleRepository.save(UserRole.builder()
                    .user(user)
                    .role(Role.CONTRIBUTOR)
                    .build());

            return new GoogleOAuth2LoginOutcome(user, true);
        }
    }
}

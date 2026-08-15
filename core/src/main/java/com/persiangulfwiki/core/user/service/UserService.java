package com.persiangulfwiki.core.user.service;

import com.persiangulfwiki.core.mail.EmailService;
import com.persiangulfwiki.core.user.dto.SessionResponse;
import com.persiangulfwiki.core.user.dto.UserProfile;
import com.persiangulfwiki.core.user.exception.SessionAlreadyRevokedException;
import com.persiangulfwiki.core.user.exception.SessionNotFoundException;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.model.RefreshToken;
import com.persiangulfwiki.core.user.repository.RefreshTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;

    public UserProfile getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + userId));
        var roles = userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRole)
                .map(Role::name)
                .toList();
        return new UserProfile(user, roles);
    }

    public List<SessionResponse> listActiveSessions(UUID userId) {
        return refreshTokenRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .stream()
                .map(rt -> new SessionResponse(rt.getId(), rt.getDeviceLabel(), rt.getIpAddress(),
                        rt.getCreatedAt(), rt.getExpiresAt()))
                .toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshToken session = refreshTokenRepository.findById(sessionId)
                .filter(rt -> rt.getUser().getId().equals(userId))
                // Collapses "no such session" and "session belongs to someone else" into the
                // same 404 — an IDOR check that avoids leaking other users' session ids via a
                // status-code oracle, same reasoning as AuthService.login's shared exception.
                .orElseThrow(SessionNotFoundException::new);
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(Instant.now());
            refreshTokenRepository.save(session);
        } else {
            throw new SessionAlreadyRevokedException();
        }
    }

    @Transactional
    public void unlinkGoogle(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + userId));

        // Idempotent: calling this on an account with no google_sub set (never linked, or
        // already unlinked) is a harmless no-op rather than a 404 — but only notify when
        // something actually changed, so an already-unlinked account doesn't get a
        // misleading "just unlinked" email on a repeat call.
        if (user.getGoogleSub() != null) {
            user.setGoogleSub(null);
            userRepository.save(user);

            // Always safe: password is mandatory for every real session by construction (a
            // Google-only account never reaches a normal authenticated endpoint until
            // complete-registration sets one), so this can never leave the account with zero
            // login paths — no "last auth method" guard needed here.
            //
            // sendGoogleAccountUnlinkedEmail is @Async: the proxy hands the call off to
            // AsyncMailConfig's executor and returns immediately, so nothing it throws (it
            // already catches MailException internally, and anything else would only reach
            // the executor's uncaught-exception handler) can propagate back into this
            // transactional method — unlike GoogleOAuth2SuccessHandler's call site, no
            // defensive try/catch is needed here.
            emailService.sendGoogleAccountUnlinkedEmail(user.getEmail());
        }
    }
}

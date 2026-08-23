package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for AuthService.register's and GoogleOAuth2SuccessHandler's lazy reclaim: those
// only fire when someone retries the same email, so this sweeps up abandoned Google-only
// rows nobody ever revisits.
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2CleanupJob {

    private final UserRepository userRepository;

    @Value("${app.oauth2.abandoned-account-ttl-hours}")
    private final long abandonedAccountTtlHours;

    // 03:00 server time — off-peak, well outside expected login traffic.
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteAbandonedInterimAccounts() {
        Instant threshold = Instant.now().minus(abandonedAccountTtlHours, ChronoUnit.HOURS);
        long deleted = userRepository.deleteByPasswordHashIsNullAndCreatedAtBefore(threshold);
        if (deleted > 0) {
            log.info("deleted {} abandoned Google-only account(s) past the {}h TTL", deleted, abandonedAccountTtlHours);
        }
    }
}

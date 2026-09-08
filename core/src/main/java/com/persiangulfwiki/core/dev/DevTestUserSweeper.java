package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestUserController's DELETE route, in the same spirit as OAuth2CleanupJob:
// the explicit path only fires when a teardown hook actually runs, so this reclaims the rows
// left behind by the runs that crashed, timed out, or were killed mid-suite. A test suite that
// cleans up after itself never needs this; a test suite that always cleans up after itself
// does not exist.
//
// Scheduling itself is enabled globally by OAuth2CleanupConfig, so this needs no @EnableScheduling
// of its own — only the @Profile("dev") gate, which keeps the bean (and therefore the job) from
// existing anywhere else.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestUserSweeper {

    private final UserRepository userRepository;

    @Value("${app.dev.test-user-ttl-hours}")
    private final long testUserTtlHours;

    // Hourly at :30 rather than OAuth2CleanupJob's nightly 03:00. These rows accumulate per CI
    // run, not per abandoned signup, so a machine running suites all afternoon would otherwise
    // carry a full day of them; and unlike that job there is no off-peak window to protect,
    // since deleting an e2e- row can never disturb a human's session.
    // @Transactional is on *this* method, not only on deleteOlderThan, and that is load-bearing:
    // the call below is a self-invocation, which does not pass back through the proxy, so
    // deleteOlderThan's own annotation is inert on this path. Without one here the derived
    // delete query would fail with TransactionRequiredException — hourly, in dev, where the
    // tests calling deleteOlderThan from outside the bean would never see it.
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public void sweepExpiredTestUsers() {
        deleteOlderThan(Instant.now().minus(testUserTtlHours, ChronoUnit.HOURS));
    }

    // Split out from the scheduled trigger so the TTL boundary is testable by passing a
    // threshold, rather than by fabricating a createdAt — which JPA cannot do anyway, since
    // AuditableEntity maps created_at as updatable = false and stamps it in @PrePersist.
    @Transactional
    public long deleteOlderThan(Instant threshold) {
        // Matches on the e2e- email prefix, so DevUserSeeder's named fixtures are untouchable
        // by this job no matter how old they get — they share the @dev.local domain but never
        // the prefix. See DevTestUsers for why the two populations are kept apart.
        long deleted = userRepository.deleteByEmailStartingWithAndCreatedAtBefore(
                DevTestUsers.EMAIL_PREFIX, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test user(s) older than the {}h TTL", deleted, testUserTtlHours);
        }
        return deleted;
    }
}

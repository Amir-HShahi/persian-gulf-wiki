package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestSubjectController's DELETE route, the same role DevTestUserSweeper
// plays for minted users: the explicit path only fires when a teardown hook actually runs,
// so this reclaims the rows left by runs that crashed, timed out, or were killed mid-suite.
//
// Scheduling is enabled globally by OAuth2CleanupConfig, so this needs no @EnableScheduling
// of its own — only the @Profile("dev") gate that keeps the bean from existing elsewhere.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestSubjectSweeper {

    private final SubjectRepository subjectRepository;

    @Value("${app.dev.test-subject-ttl-hours}")
    private final long testSubjectTtlHours;

    // Offset from DevTestUserSweeper's :30 so the two bulk deletes don't contend for the same
    // rows' foreign keys in the same minute on a busy dev database.
    // @Transactional is on *this* method, not only on deleteOlderThan, and that is
    // load-bearing: the call below is a self-invocation, which does not pass back through the
    // proxy, so deleteOlderThan's own annotation is inert on this path.
    @Scheduled(cron = "0 40 * * * *")
    @Transactional
    public void sweepExpiredTestSubjects() {
        deleteOlderThan(Instant.now().minus(testSubjectTtlHours, ChronoUnit.HOURS));
    }

    // Split out from the scheduled trigger so the TTL boundary is testable by passing a
    // threshold, rather than by fabricating a createdAt — which JPA cannot do anyway, since
    // created_at is mapped updatable = false and stamped in @PrePersist.
    @Transactional
    public long deleteOlderThan(Instant threshold) {
        // Matches on the dev marker, so a subject a human created through the normal endpoint
        // is untouchable by this job no matter how old it gets — its marker is null.
        long deleted = subjectRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test subject(s) older than the {}h TTL", deleted, testSubjectTtlHours);
        }
        return deleted;
    }
}

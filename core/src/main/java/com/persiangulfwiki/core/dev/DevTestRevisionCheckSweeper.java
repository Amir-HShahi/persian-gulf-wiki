package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestRevisionCheckController's DELETE route -- see DevTestSubjectSweeper for the
// general reasoning, which is identical. A check minted on a test article's revision usually goes
// first, with the revision (revision_checks.revision_id is ON DELETE CASCADE); this catches the
// ones minted on a revision that outlives the TTL.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestRevisionCheckSweeper {

    private final RevisionCheckRepository revisionCheckRepository;

    @Value("${app.dev.test-revision-check-ttl-hours}")
    private final long testRevisionCheckTtlHours;

    // :00, the one free slot. Nothing points at revision_checks, and it points only at revisions
    // (CASCADE), so no other sweep can block this one or be blocked by it. @Transactional here for
    // the same self-invocation reason as DevTestSubjectSweeper.sweepExpiredTestSubjects.
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void sweepExpiredTestRevisionChecks() {
        deleteOlderThan(Instant.now().minus(testRevisionCheckTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = revisionCheckRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test revision check(s) older than the {}h TTL", deleted,
                    testRevisionCheckTtlHours);
        }
        return deleted;
    }
}

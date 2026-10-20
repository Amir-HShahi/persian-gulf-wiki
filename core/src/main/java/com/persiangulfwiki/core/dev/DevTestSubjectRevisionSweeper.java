package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestSubjectRevisionController's DELETE route -- see DevTestSubjectSweeper for the
// reasoning. A live revision is skipped rather than deleted: its subject is a test subject (a
// fixture can only be made live on one, and an approved fixture proposal creates one), so
// DevTestSubjectSweeper reclaims it at :40 with every revision of that subject.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestSubjectRevisionSweeper {

    private final SubjectRevisionRepository subjectRevisionRepository;

    @Value("${app.dev.test-subject-revision-ttl-hours}")
    private final long testSubjectRevisionTtlHours;

    // :35, a minute no other sweeper uses. Annotated here as well as on deleteOlderThan because
    // the call below is a self-invocation -- see DevTestSubjectSweeper.
    @Scheduled(cron = "0 35 * * * *")
    @Transactional
    public void sweepExpiredTestSubjectRevisions() {
        deleteOlderThan(Instant.now().minus(testSubjectRevisionTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = subjectRevisionRepository.deleteUnpublishedByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER,
                threshold);
        if (deleted > 0) {
            log.info("swept {} minted test subject revision(s) older than the {}h TTL", deleted,
                    testSubjectRevisionTtlHours);
        }
        return deleted;
    }
}

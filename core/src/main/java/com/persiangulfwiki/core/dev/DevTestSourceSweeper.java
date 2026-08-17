package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.source.repository.SourceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestSourceController's DELETE route — see DevTestSubjectSweeper for the
// reasoning, which is identical.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestSourceSweeper {

    private final SourceRepository sourceRepository;

    @Value("${app.dev.test-source-ttl-hours}")
    private final long testSourceTtlHours;

    @Scheduled(cron = "0 50 * * * *")
    @Transactional
    public void sweepExpiredTestSources() {
        deleteOlderThan(Instant.now().minus(testSourceTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = sourceRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test source(s) older than the {}h TTL", deleted, testSourceTtlHours);
        }
        return deleted;
    }
}

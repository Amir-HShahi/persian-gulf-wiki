package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestMarineController's DELETE route, the same role DevTestSubjectSweeper
// plays for subjects: reclaims the stations left by suites that never ran their teardown.
// Only rows under the dev-test- station prefix are candidates, so real stations' history is
// untouchable however old it gets.
//
// Scheduling is enabled globally by OAuth2CleanupConfig.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestMarineSweeper {

    private final MarineMetricReadingRepository readingRepository;
    private final MarineSourceSyncRepository syncRepository;

    @Value("${app.dev.test-marine-ttl-hours}")
    private final long testMarineTtlHours;

    // :45, the free slot between the other dev sweepers. Marine rows reference nothing else,
    // so ordering against them does not matter beyond not piling up in the same minute.
    // @Transactional sits here because the call below is a self-invocation.
    @Scheduled(cron = "0 45 * * * *")
    @Transactional
    public void sweepExpiredTestStations() {
        deleteOlderThan(Instant.now().minus(testMarineTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = readingRepository.deleteByStationKeyStartingWithAndFetchedAtBefore(
                DevTestMarineStations.STATION_PREFIX, threshold)
                + syncRepository.deleteByIdStationKeyStartingWithAndLastAttemptAtBefore(
                        DevTestMarineStations.STATION_PREFIX, threshold);
        if (deleted > 0) {
            log.info("swept {} minted marine row(s) older than the {}h TTL", deleted, testMarineTtlHours);
        }
        return deleted;
    }
}

package com.persiangulfwiki.core.marinedata.service;

import com.persiangulfwiki.core.marinedata.MarineDataConfig;
import com.persiangulfwiki.core.marinedata.MarineDataProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.SourceProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.Station;
import com.persiangulfwiki.core.marinedata.client.MarineDataClient;
import com.persiangulfwiki.core.marinedata.client.ProviderReading;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarineDataIngestService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final List<MarineDataClient> clients;
    private final MarineDataProperties properties;
    private final MarineMetricReadingRepository readingRepository;
    private final MarineSourceSyncRepository syncRepository;
    private final TransactionTemplate transactionTemplate;
    private final CacheManager cacheManager;

    /**
     * Fetches every (source, station) pair whose interval has elapsed since its last attempt,
     * then clears both read caches if any pair succeeded. One pair failing never stops the
     * others; its failure is recorded on its sync row and the previous lastSyncedAt is kept.
     */
    public void ingestDue(Instant now) {
        boolean anySucceeded = false;
        for (MarineDataClient client : clients) {
            SourceProperties source = properties.source(client.source());
            for (Station station : source.stations()) {
                MarineSourceSyncId id = new MarineSourceSyncId(client.source().name(), station.key());
                Optional<MarineSourceSync> sync = syncRepository.findById(id);
                if (sync.isPresent() && sync.get().getLastAttemptAt().plus(source.interval()).isAfter(now)) {
                    continue;
                }
                anySucceeded |= ingestPair(client, station, id, sync.orElse(null), now);
            }
        }
        if (anySucceeded) {
            evict(MarineDataConfig.LATEST_CACHE);
            evict(MarineDataConfig.SERIES_CACHE);
        }
    }

    private boolean ingestPair(MarineDataClient client, Station station, MarineSourceSyncId id,
            @Nullable MarineSourceSync existing, Instant now) {
        MarineSourceSync sync = existing != null ? existing : MarineSourceSync.builder().id(id).build();
        sync.setLastAttemptAt(now);
        try {
            // The network call stays outside the transaction so a slow provider never holds a
            // connection open; only the writes are transactional.
            List<ProviderReading> readings = client.fetch(station, now).stream()
                    // Open-Meteo returns forecast hours alongside past ones; only what has
                    // already happened is stored.
                    .filter(reading -> !reading.observedAt().isAfter(now))
                    .toList();
            transactionTemplate.executeWithoutResult(_ -> {
                for (ProviderReading reading : readings) {
                    readingRepository.upsert(reading.metric().getKey(), station.key(), reading.observedAt(),
                            reading.value(), client.source().name(), now);
                }
                sync.setLastSuccessAt(now);
                sync.setLastError(null);
                syncRepository.save(sync);
            });
            return true;
        } catch (RuntimeException ex) {
            // Logged here, once, because nothing above catches it: this is the boundary.
            log.warn("marine data fetch failed for {} at {}: {}", client.source(), station.key(), ex.toString());
            sync.setLastError(truncate(ex.toString()));
            syncRepository.save(sync);
            return false;
        }
    }

    private void evict(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.invalidate();
        }
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}

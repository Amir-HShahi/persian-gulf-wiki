package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestMarineReadingPointRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMarineReadingRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMarineReadingResponse;
import com.persiangulfwiki.core.dev.exception.InvalidDevTestMarineRequestException;
import com.persiangulfwiki.core.marinedata.MarineDataConfig;
import com.persiangulfwiki.core.marinedata.entity.MarineMetric;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;
import com.persiangulfwiki.core.marinedata.exception.MarineStationNotFoundException;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

// Mints a throwaway station with chosen readings and sync state, so an E2E suite can render
// fresh, stale, alerting or empty-sparkline cards without waiting on real providers.
//
// Two independent gates keep this out of production, exactly as for DevTestUserController:
// @Profile("dev") here, and DevSecurityConfig's profiled filter chain for /api/dev/**.
//
// Bypasses the ingest service on purpose: that path only ever stamps lastSyncedAt with "now"
// and only stores what a provider returned, so a stale card or a chosen value is unreachable
// through it. Writes go straight through the repositories instead.
@RestController
@RequestMapping("/api/dev/marine-readings")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Marine Data", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestMarineController {

    private static final int SLUG_LENGTH = 12;

    private final MarineMetricReadingRepository readingRepository;
    private final MarineSourceSyncRepository syncRepository;
    private final CacheManager cacheManager;

    @Operation(summary = "Mint a disposable test station", description = "Creates a new station with the given readings "
            + "for one metric, plus its sync state. `lastSyncedAt` defaults to now; send an old time to get a card "
            + "that is already stale. The station then appears in the public marine data endpoints like a real one.")
    @ApiResponse(responseCode = "201", description = "The minted station's key.")
    @ApiResponse(responseCode = "400", description = "Unknown `metricKey`, no readings, or a reading with a missing "
            + "or out-of-range field.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO rather than the usual envelope, matching the other dev fixture endpoints.
    public DevTestMarineReadingResponse mint(@Valid @RequestBody DevTestMarineReadingRequest request) {
        MarineMetric metric = MarineMetric.fromKey(request.metricKey())
                .orElseThrow(() -> new InvalidDevTestMarineRequestException("unknown metric " + request.metricKey()));
        String stationKey = DevTestMarineStations.STATION_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        Instant now = Instant.now();

        for (DevTestMarineReadingPointRequest reading : request.readings()) {
            readingRepository.upsert(metric.getKey(), stationKey, reading.observedAt(), reading.value(),
                    metric.getSource().name(), now);
        }
        // last_attempt_at is the mint time, not the lastSyncedAt override, so the sweeper's age
        // check measures how long the fixture has existed rather than how stale it pretends to be.
        syncRepository.save(MarineSourceSync.builder()
                .id(new MarineSourceSyncId(metric.getSource().name(), stationKey))
                .lastAttemptAt(now)
                .lastSuccessAt(request.lastSyncedAt() != null ? request.lastSyncedAt() : now)
                .build());

        // The public endpoints cache for up to an hour; without this the new station would not
        // show until the next ingest.
        clearCaches();
        return new DevTestMarineReadingResponse(stationKey, metric.getKey(), request.readings().size());
    }

    @Operation(summary = "Delete a minted test station", description = "Deletes every reading and sync row of a station "
            + "this endpoint minted. Refuses any other station — a real one is reported as 404 rather than deleted. "
            + "Idempotent: deleting a station twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The station was deleted.")
    @ApiResponse(responseCode = "404", description = "No such station, or it is not one this endpoint minted.")
    @DeleteMapping("/{stationKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable String stationKey) {
        // The prefix check is what makes handing an arbitrary key to this route safe: a teardown
        // hook that lost track of its fixture cannot wipe a real station's history.
        if (!stationKey.startsWith(DevTestMarineStations.STATION_PREFIX)) {
            throw new MarineStationNotFoundException("not a minted station: " + stationKey);
        }
        long deleted = readingRepository.deleteByStationKey(stationKey) + syncRepository.deleteByIdStationKey(stationKey);
        if (deleted == 0) {
            throw new MarineStationNotFoundException("no such minted station: " + stationKey);
        }
        clearCaches();
    }

    private void clearCaches() {
        for (String name : new String[] {MarineDataConfig.LATEST_CACHE, MarineDataConfig.SERIES_CACHE}) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.invalidate();
            }
        }
    }
}

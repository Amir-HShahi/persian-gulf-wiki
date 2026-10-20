package com.persiangulfwiki.core.marinedata;

import com.persiangulfwiki.core.marinedata.service.MarineDataIngestService;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Ticks every five minutes; each source still runs only at its own configured interval (see
 * {@link MarineDataIngestService#ingestDue}). Assumes a single {@code core} instance: if it is
 * ever scaled out, guard this with ShedLock or every replica will call the providers.
 */
// Scheduling is enabled globally by OAuth2CleanupConfig.
@Component
@RequiredArgsConstructor
public class MarineDataIngestJob {

    private final MarineDataIngestService ingestService;
    private final MarineDataProperties properties;

    @Scheduled(initialDelay = 1, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void tick() {
        if (properties.ingestEnabled()) {
            ingestService.ingestDue(Instant.now());
        }
    }
}

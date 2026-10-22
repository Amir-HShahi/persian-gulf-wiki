package com.persiangulfwiki.core.marinedata.service;

import com.persiangulfwiki.core.marinedata.MarineDataConfig;
import com.persiangulfwiki.core.marinedata.MarineDataProperties;
import com.persiangulfwiki.core.marinedata.dto.MarineMetricResponse;
import com.persiangulfwiki.core.marinedata.dto.MarineSeriesPointResponse;
import com.persiangulfwiki.core.marinedata.dto.MarineSeriesResponse;
import com.persiangulfwiki.core.marinedata.entity.MarineGranularity;
import com.persiangulfwiki.core.marinedata.entity.MarineMetric;
import com.persiangulfwiki.core.marinedata.entity.MarineSeriesRange;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;
import com.persiangulfwiki.core.marinedata.exception.InvalidMarineSeriesRangeException;
import com.persiangulfwiki.core.marinedata.exception.MarineMetricNotFoundException;
import com.persiangulfwiki.core.marinedata.exception.MarineStationNotFoundException;
import com.persiangulfwiki.core.marinedata.exception.UnsupportedMarineSeriesRangeException;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository.LatestReadingView;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository.SeriesPointView;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MarineMetricService {

    private static final int BASELINE_DAYS = 7;
    private static final int SPARKLINE_DAYS = 7;

    private final MarineMetricReadingRepository readingRepository;
    private final MarineSourceSyncRepository syncRepository;
    private final MarineDataProperties properties;

    // One entry per (metric, station) that has at least one reading — stations come from the
    // data, not from config, so a station dropped from config keeps showing (and going stale)
    // rather than vanishing, and a dev fixture station shows up like a real one.
    @Cacheable(cacheManager = MarineDataConfig.CACHE_MANAGER, cacheNames = MarineDataConfig.LATEST_CACHE, key = "'all'")
    @Transactional(readOnly = true)
    public List<MarineMetricResponse> latest() {
        List<MarineMetricResponse> cards = new ArrayList<>();
        for (LatestReadingView latest : readingRepository.findLatestPerMetricAndStation()) {
            // A key no longer in the enum (a retired metric) is skipped, not an error.
            MarineMetric.fromKey(latest.getMetricKey()).ifPresent(metric -> cards.add(toCard(metric, latest)));
        }
        return cards;
    }

    // Cached only on success: an invalid key or range throws before anything is stored, so a
    // caller cannot fill the cache with junk keys.
    @Cacheable(cacheManager = MarineDataConfig.CACHE_MANAGER, cacheNames = MarineDataConfig.SERIES_CACHE, key = "#metricKey + ':' + #stationKey + ':' + #range.toUpperCase()")
    @Transactional(readOnly = true)
    public MarineSeriesResponse series(String metricKey, String stationKey, String range) {
        MarineMetric metric = MarineMetric.fromKey(metricKey)
                .orElseThrow(() -> new MarineMetricNotFoundException("unknown marine metric: " + metricKey));
        MarineSeriesRange seriesRange = parseRange(range);
        if (seriesRange.getBucketUnit() == ChronoUnit.HOURS && metric.getGranularity() == MarineGranularity.DAILY) {
            throw new UnsupportedMarineSeriesRangeException(
                    "range " + seriesRange + " not supported for daily metric " + metricKey);
        }
        if (!readingRepository.existsByMetricKeyAndStationKey(metricKey, stationKey)) {
            throw new MarineStationNotFoundException("no readings for " + metricKey + " at " + stationKey);
        }
        // The window ends with the current (partial) bucket and reaches back bucketCount buckets.
        Instant end = Instant.now().truncatedTo(seriesRange.getBucketUnit()).plus(1, seriesRange.getBucketUnit());
        Instant start = end.minus(seriesRange.getBucketCount(), seriesRange.getBucketUnit());
        List<MarineSeriesPointResponse> points = points(metric, stationKey, seriesRange.bucketField(), start, end);
        return new MarineSeriesResponse(metricKey, stationKey, seriesRange.name(), metric.getUnit(), points);
    }

    private MarineMetricResponse toCard(MarineMetric metric, LatestReadingView latest) {
        Instant observedAt = latest.getObservedAt();
        BigDecimal baseline = readingRepository.averageBetween(metric.getKey(), latest.getStationKey(),
                observedAt.minus(BASELINE_DAYS, ChronoUnit.DAYS), observedAt);
        BigDecimal delta = baseline == null ? null : latest.getValue().subtract(baseline);

        // Anchored on the latest reading's day, not today: NOAA's daily products lag by days,
        // and a window ending today would show them a half-empty sparkline.
        Instant sparklineEnd = observedAt.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
        List<MarineSeriesPointResponse> sparkline = points(metric, latest.getStationKey(), "day",
                sparklineEnd.minus(SPARKLINE_DAYS, ChronoUnit.DAYS), sparklineEnd);

        Optional<MarineSourceSync> sync = syncRepository.findById(
                new MarineSourceSyncId(metric.getSource().name(), latest.getStationKey()));
        Instant lastSyncedAt = sync.map(MarineSourceSync::getLastSuccessAt).orElse(null);
        Instant staleAfter = staleAfter(metric, lastSyncedAt);

        return new MarineMetricResponse(metric.getKey(), latest.getStationKey(), latest.getValue(), metric.getUnit(),
                observedAt, delta, metric.statusFor(latest.getValue()), sparkline, lastSyncedAt, staleAfter,
                metric.getSource().getSourceName(), metric.getSource().getSourceLicense());
    }

    // Two missed intervals, so one slow or skipped tick does not flip a card to stale.
    private @Nullable Instant staleAfter(MarineMetric metric, @Nullable Instant lastSyncedAt) {
        if (lastSyncedAt == null) {
            return null;
        }
        return lastSyncedAt.plus(properties.source(metric.getSource()).interval().multipliedBy(2));
    }

    private List<MarineSeriesPointResponse> points(MarineMetric metric, String stationKey, String bucketField,
            Instant from, Instant to) {
        List<SeriesPointView> rows = switch (metric.getAggregation()) {
            case AVG -> readingRepository.averageSeries(metric.getKey(), stationKey, bucketField, from, to);
            case MAX -> readingRepository.maxSeries(metric.getKey(), stationKey, bucketField, from, to);
        };
        return rows.stream()
                .map(row -> new MarineSeriesPointResponse(row.getBucketStart(), row.getValue()))
                .toList();
    }

    private MarineSeriesRange parseRange(String range) {
        try {
            return MarineSeriesRange.valueOf(range.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidMarineSeriesRangeException("invalid marine series range: " + range);
        }
    }
}

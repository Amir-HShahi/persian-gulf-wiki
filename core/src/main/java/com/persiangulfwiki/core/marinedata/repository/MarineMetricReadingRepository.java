package com.persiangulfwiki.core.marinedata.repository;

import com.persiangulfwiki.core.marinedata.entity.MarineMetricReading;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface MarineMetricReadingRepository extends JpaRepository<MarineMetricReading, Long> {

    // A re-sent (metric, station, observed_at) overwrites the stored value: providers revise
    // recent hours, and every call re-sends the whole look-back window.
    @Modifying
    @Query(value = """
            INSERT INTO marine_metric_reading (metric_key, station_key, observed_at, value, source_key, fetched_at)
            VALUES (:metricKey, :stationKey, :observedAt, :value, :sourceKey, :fetchedAt)
            ON CONFLICT (metric_key, station_key, observed_at)
            DO UPDATE SET value = EXCLUDED.value, source_key = EXCLUDED.source_key, fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsert(@Param("metricKey") String metricKey, @Param("stationKey") String stationKey,
            @Param("observedAt") Instant observedAt, @Param("value") BigDecimal value,
            @Param("sourceKey") String sourceKey, @Param("fetchedAt") Instant fetchedAt);

    @Query(value = """
            SELECT DISTINCT ON (metric_key, station_key)
                   metric_key AS metricKey, station_key AS stationKey, observed_at AS observedAt, value AS value
            FROM marine_metric_reading
            ORDER BY metric_key, station_key, observed_at DESC
            """, nativeQuery = true)
    List<LatestReadingView> findLatestPerMetricAndStation();

    /** Half-open window {@code [from, to)}; null when it holds no readings. */
    @Query(value = """
            SELECT round(avg(value), 4) FROM marine_metric_reading
            WHERE metric_key = :metricKey AND station_key = :stationKey
              AND observed_at >= :from AND observed_at < :to
            """, nativeQuery = true)
    @Nullable BigDecimal averageBetween(@Param("metricKey") String metricKey, @Param("stationKey") String stationKey,
            @Param("from") Instant from, @Param("to") Instant to);

    // Two queries rather than one with the aggregate spliced in: the function name cannot be
    // a bind parameter, and building SQL from strings is exactly what this layer avoids.
    // bucketField is "hour" or "day" from MarineSeriesRange, never caller input. Buckets
    // with no readings are absent rather than null-filled.
    @Query(value = """
            SELECT date_trunc(:bucketField, observed_at, 'UTC') AS bucketStart, round(avg(value), 4) AS value
            FROM marine_metric_reading
            WHERE metric_key = :metricKey AND station_key = :stationKey
              AND observed_at >= :from AND observed_at < :to
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<SeriesPointView> averageSeries(@Param("metricKey") String metricKey, @Param("stationKey") String stationKey,
            @Param("bucketField") String bucketField, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT date_trunc(:bucketField, observed_at, 'UTC') AS bucketStart, max(value) AS value
            FROM marine_metric_reading
            WHERE metric_key = :metricKey AND station_key = :stationKey
              AND observed_at >= :from AND observed_at < :to
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<SeriesPointView> maxSeries(@Param("metricKey") String metricKey, @Param("stationKey") String stationKey,
            @Param("bucketField") String bucketField, @Param("from") Instant from, @Param("to") Instant to);

    boolean existsByMetricKeyAndStationKey(String metricKey, String stationKey);

    long deleteByStationKey(String stationKey);

    long deleteByStationKeyStartingWithAndFetchedAtBefore(String stationKeyPrefix, Instant threshold);

    interface LatestReadingView {
        String getMetricKey();

        String getStationKey();

        Instant getObservedAt();

        BigDecimal getValue();
    }

    interface SeriesPointView {
        Instant getBucketStart();

        BigDecimal getValue();
    }
}

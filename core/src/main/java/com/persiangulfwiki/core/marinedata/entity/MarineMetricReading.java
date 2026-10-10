package com.persiangulfwiki.core.marinedata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

// Immutable once written from Java's point of view: every insert goes through
// MarineMetricReadingRepository.upsert, so this mapping exists for reads and deletes only.
@Entity
@Table(name = "marine_metric_reading")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"metricKey", "stationKey", "observedAt"})
public class MarineMetricReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false, length = 64)
    private String metricKey;

    @Column(name = "station_key", nullable = false, length = 64)
    private String stationKey;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal value;

    @Column(name = "source_key", nullable = false, length = 32)
    private String sourceKey;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}

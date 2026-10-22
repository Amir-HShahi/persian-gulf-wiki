package com.persiangulfwiki.core.marinedata.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

// The metrics behind the homepage data cards. The key is the stable identifier stored in
// marine_metric_reading and returned by the API; the frontend owns labels and unit display.
// Status thresholds live here rather than in config: they are published scientific/regulatory
// bands, not deployment knobs.
@Getter
@RequiredArgsConstructor
public enum MarineMetric {

    SEA_SURFACE_TEMPERATURE("sea_surface_temperature", MarineSource.OPEN_METEO_MARINE,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "celsius"),
    SEA_SURFACE_TEMPERATURE_ANOMALY("sea_surface_temperature_anomaly", MarineSource.NOAA_ERDDAP,
            MarineGranularity.DAILY, MarineAggregation.AVG, "celsius"),
    CORAL_BLEACHING_ALERT("coral_bleaching_alert", MarineSource.NOAA_ERDDAP,
            MarineGranularity.DAILY, MarineAggregation.MAX, "level"),
    DEGREE_HEATING_WEEKS("degree_heating_weeks", MarineSource.NOAA_ERDDAP,
            MarineGranularity.DAILY, MarineAggregation.MAX, "celsius_weeks"),
    AIR_QUALITY_INDEX("air_quality_index", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "index"),
    PM2_5("pm2_5", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "ug_m3"),
    PM10("pm10", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "ug_m3"),
    SULPHUR_DIOXIDE("sulphur_dioxide", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "ug_m3"),
    NITROGEN_DIOXIDE("nitrogen_dioxide", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "ug_m3"),
    DUST("dust", MarineSource.OPEN_METEO_AIR_QUALITY,
            MarineGranularity.HOURLY, MarineAggregation.AVG, "ug_m3");

    private static final BigDecimal SST_ANOMALY_WARNING = BigDecimal.ONE;

    private final String key;
    private final MarineSource source;
    private final MarineGranularity granularity;
    private final MarineAggregation aggregation;
    private final String unit;

    public static Optional<MarineMetric> fromKey(String key) {
        return Arrays.stream(values()).filter(metric -> metric.key.equals(key)).findFirst();
    }

    /** Returns null for metrics that carry no status bands (the raw pollutant concentrations, DHW). */
    @Nullable
    public MarineStatus statusFor(BigDecimal value) {
        return switch (this) {
            // NOAA Coral Reef Watch Bleaching Alert Area: 0 no stress, 1 watch, 2 warning,
            // 3-4 alert levels 1-2.
            case CORAL_BLEACHING_ALERT -> switch (value.intValue()) {
                case 0 -> MarineStatus.NORMAL;
                case 1 -> MarineStatus.WATCH;
                case 2 -> MarineStatus.WARNING;
                default -> value.signum() < 0 ? MarineStatus.NORMAL : MarineStatus.ALERT;
            };
            // European AQI bands: good/fair (0-40), moderate (40-60), poor (60-80),
            // very poor and worse (80+).
            case AIR_QUALITY_INDEX -> {
                double aqi = value.doubleValue();
                if (aqi <= 40) {
                    yield MarineStatus.NORMAL;
                }
                if (aqi <= 60) {
                    yield MarineStatus.WATCH;
                }
                yield aqi <= 80 ? MarineStatus.WARNING : MarineStatus.ALERT;
            }
            case SEA_SURFACE_TEMPERATURE_ANOMALY ->
                    value.compareTo(SST_ANOMALY_WARNING) >= 0 ? MarineStatus.WARNING : MarineStatus.NORMAL;
            default -> null;
        };
    }
}

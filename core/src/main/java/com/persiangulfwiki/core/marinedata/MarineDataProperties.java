package com.persiangulfwiki.core.marinedata;

import com.persiangulfwiki.core.marinedata.entity.MarineSource;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

// Bound as a record rather than through @Value fields because the station lists are
// structured yaml, not scalar env vars; only ingestEnabled is a placeholder. Base URLs are
// fixed here and never come from a request, so the outbound clients have no SSRF surface.
@ConfigurationProperties("app.marine-data")
public record MarineDataProperties(
        boolean ingestEnabled,
        Duration connectTimeout,
        Duration readTimeout,
        SourceProperties openMeteoMarine,
        SourceProperties openMeteoAirQuality,
        SourceProperties noaaErddap) {

    public SourceProperties source(MarineSource source) {
        return switch (source) {
            case OPEN_METEO_MARINE -> openMeteoMarine;
            case OPEN_METEO_AIR_QUALITY -> openMeteoAirQuality;
            case NOAA_ERDDAP -> noaaErddap;
        };
    }

    // lookbackDays is how much history each call re-requests. The upsert makes the overlap
    // free, and a fresh deploy gets a full week of sparkline on its first tick.
    public record SourceProperties(String baseUrl, Duration interval, int lookbackDays, List<Station> stations) {

        public SourceProperties {
            stations = stations == null ? List.of() : List.copyOf(stations);
        }
    }

    public record Station(String key, double latitude, double longitude) {
    }
}

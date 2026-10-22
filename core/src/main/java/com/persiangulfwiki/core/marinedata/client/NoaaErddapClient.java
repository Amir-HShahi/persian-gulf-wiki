package com.persiangulfwiki.core.marinedata.client;

import com.persiangulfwiki.core.marinedata.MarineDataProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.SourceProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.Station;
import com.persiangulfwiki.core.marinedata.entity.MarineMetric;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// NOAA Coral Reef Watch's daily 5 km products, one griddap dataset per variable, on the
// CoastWatch ERDDAP server. The combined NOAA_DHW dataset on the pfeg mirror was tried first
// and does not resolve there, so these three are the verified ids. Each publishes one value
// per day stamped 12:00Z, typically one to several days behind.
@Component
@RequiredArgsConstructor
public class NoaaErddapClient implements MarineDataClient {

    private static final Map<MarineMetric, Dataset> DATASETS = Map.of(
            MarineMetric.SEA_SURFACE_TEMPERATURE_ANOMALY,
            new Dataset("noaacrwsstanomalyDaily", "sea_surface_temperature_anomaly"),
            MarineMetric.CORAL_BLEACHING_ALERT, new Dataset("noaacrwbaa7dDaily", "bleaching_alert_area"),
            MarineMetric.DEGREE_HEATING_WEEKS, new Dataset("noaacrwdhwDaily", "degree_heating_week"));

    private final RestClient marineDataRestClient;
    private final MarineDataProperties properties;

    @Override
    public MarineSource source() {
        return MarineSource.NOAA_ERDDAP;
    }

    @Override
    public List<ProviderReading> fetch(Station station, Instant now) {
        SourceProperties source = properties.source(source());
        Instant from = now.truncatedTo(ChronoUnit.DAYS).minus(source.lookbackDays(), ChronoUnit.DAYS);
        List<ProviderReading> readings = new ArrayList<>();
        for (Map.Entry<MarineMetric, Dataset> entry : DATASETS.entrySet()) {
            Dataset dataset = entry.getValue();
            // griddap selects by [time][latitude][longitude]; parenthesised values are matched
            // to the nearest grid cell, and (last) is the newest published day.
            String query = dataset.variable() + "[(" + from + "):1:(last)]"
                    + "[(" + station.latitude() + ")][(" + station.longitude() + ")]";
            URI uri = URI.create(source.baseUrl() + "/griddap/" + dataset.id() + ".json?"
                    + URLEncoder.encode(query, StandardCharsets.UTF_8));
            JsonNode body = marineDataRestClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (body != null) {
                readings.addAll(parse(body, entry.getKey()));
            }
        }
        return readings;
    }

    // Rows are [time, latitude, longitude, value]; a land or ice cell's value is null.
    private static List<ProviderReading> parse(JsonNode body, MarineMetric metric) {
        List<ProviderReading> readings = new ArrayList<>();
        for (JsonNode row : body.path("table").path("rows")) {
            JsonNode value = row.get(3);
            if (value != null && value.isNumber() && Double.isFinite(value.asDouble())) {
                readings.add(new ProviderReading(metric, Instant.parse(row.get(0).asString()),
                        BigDecimal.valueOf(value.asDouble())));
            }
        }
        return readings;
    }

    private record Dataset(String id, String variable) {
    }
}

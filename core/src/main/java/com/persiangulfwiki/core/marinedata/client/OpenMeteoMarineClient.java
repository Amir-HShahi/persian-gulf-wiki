package com.persiangulfwiki.core.marinedata.client;

import com.persiangulfwiki.core.marinedata.MarineDataProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.SourceProperties;
import com.persiangulfwiki.core.marinedata.MarineDataProperties.Station;
import com.persiangulfwiki.core.marinedata.entity.MarineMetric;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OpenMeteoMarineClient implements MarineDataClient {

    private static final Map<MarineMetric, String> VARIABLES =
            Map.of(MarineMetric.SEA_SURFACE_TEMPERATURE, "sea_surface_temperature");

    private final RestClient marineDataRestClient;
    private final MarineDataProperties properties;

    @Override
    public MarineSource source() {
        return MarineSource.OPEN_METEO_MARINE;
    }

    @Override
    public List<ProviderReading> fetch(Station station, Instant now) {
        SourceProperties source = properties.source(source());
        URI uri = UriComponentsBuilder.fromUriString(source.baseUrl())
                .path("/v1/marine")
                .queryParam("latitude", station.latitude())
                .queryParam("longitude", station.longitude())
                .queryParam("hourly", String.join(",", VARIABLES.values()))
                .queryParam("past_days", source.lookbackDays())
                .queryParam("forecast_days", 1)
                .queryParam("timeformat", "unixtime")
                .build()
                .toUri();
        JsonNode body = marineDataRestClient.get().uri(uri).retrieve().body(JsonNode.class);
        return body == null ? List.of() : OpenMeteoHourlyParser.parse(body, VARIABLES);
    }
}

package com.persiangulfwiki.core.marinedata.client;

import com.persiangulfwiki.core.marinedata.entity.MarineMetric;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Shared by the two Open-Meteo clients, whose responses have the same shape: hourly.time is
// an array of epoch seconds (requested with timeformat=unixtime) and each variable is a
// parallel array in which a missing hour is null.
final class OpenMeteoHourlyParser {

    private OpenMeteoHourlyParser() {
    }

    static List<ProviderReading> parse(JsonNode body, Map<MarineMetric, String> variables) {
        JsonNode hourly = body.path("hourly");
        JsonNode times = hourly.path("time");
        List<ProviderReading> readings = new ArrayList<>();
        for (Map.Entry<MarineMetric, String> entry : variables.entrySet()) {
            JsonNode values = hourly.path(entry.getValue());
            for (int i = 0; i < times.size() && i < values.size(); i++) {
                JsonNode value = values.get(i);
                if (value.isNumber() && Double.isFinite(value.asDouble())) {
                    readings.add(new ProviderReading(entry.getKey(),
                            Instant.ofEpochSecond(times.get(i).asLong()), BigDecimal.valueOf(value.asDouble())));
                }
            }
        }
        return readings;
    }
}

package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs under the dev profile, the only profile in which the fixture controller exists. Asserts
// on the station it minted rather than on totals, since the context is shared with the other
// dev-profile suites.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestMarineEndpointIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MarineMetricReadingRepository readingRepository;

    @Autowired
    private MarineSourceSyncRepository syncRepository;

    @Autowired
    private DevTestMarineSweeper sweeper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void mintedStaleStationAppearsPubliclyAndIsDeletedByItsKey() throws Exception {
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant lastSyncedAt = observedAt.minus(3, ChronoUnit.DAYS);
        String stationKey = mint("""
                {"metricKey":"coral_bleaching_alert",
                 "readings":[{"observedAt":"%s","value":2}],
                 "lastSyncedAt":"%s"}""".formatted(observedAt, lastSyncedAt));

        assertThat(stationKey).startsWith("dev-test-");
        // Visible immediately: minting clears the public cache.
        mockMvc.perform(get("/api/marine-metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.stationKey == '%s')]".formatted(stationKey), hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.stationKey == '%s')].status".formatted(stationKey)).value("WARNING"))
                // Six-hourly source, so stale two intervals after the backdated sync.
                .andExpect(jsonPath("$.data[?(@.stationKey == '%s')].staleAfter".formatted(stationKey))
                        .value(lastSyncedAt.plus(12, ChronoUnit.HOURS).toString()));

        mockMvc.perform(delete("/api/dev/marine-readings/{stationKey}", stationKey))
                .andExpect(status().isNoContent());

        assertThat(readingRepository.existsByMetricKeyAndStationKey("coral_bleaching_alert", stationKey)).isFalse();
        mockMvc.perform(get("/api/marine-metrics"))
                .andExpect(jsonPath("$.data[?(@.stationKey == '%s')]".formatted(stationKey), hasSize(0)));
        mockMvc.perform(delete("/api/dev/marine-readings/{stationKey}", stationKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRefusesAStationItDidNotMint() throws Exception {
        String realStation = "bushehr-dev-check";
        transactionTemplate.executeWithoutResult(_ -> readingRepository.upsert("pm10", realStation,
                Instant.now(), BigDecimal.ONE, MarineSource.OPEN_METEO_AIR_QUALITY.name(), Instant.now()));

        mockMvc.perform(delete("/api/dev/marine-readings/{stationKey}", realStation))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MARINE_STATION_NOT_FOUND"));

        assertThat(readingRepository.existsByMetricKeyAndStationKey("pm10", realStation)).isTrue();
        transactionTemplate.executeWithoutResult(_ -> readingRepository.deleteByStationKey(realStation));
    }

    @Test
    void invalidMintRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/dev/marine-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricKey\":\"sea_level\",\"readings\":[{\"observedAt\":\"2026-01-01T00:00:00Z\",\"value\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_MARINE_REQUEST"));
        mockMvc.perform(post("/api/dev/marine-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricKey\":\"pm10\",\"readings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/dev/marine-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricKey\":\"pm10\",\"readings\":[{\"observedAt\":\"2026-01-01T00:00:00Z\","
                                + "\"value\":123456789}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void sweeperRemovesOnlyExpiredMintedStations() throws Exception {
        String minted = mint("""
                {"metricKey":"pm10","readings":[{"observedAt":"%s","value":5}]}""".formatted(Instant.now()));
        String realStation = "sweeper-real-station";
        transactionTemplate.executeWithoutResult(_ -> readingRepository.upsert("pm10", realStation,
                Instant.now(), BigDecimal.ONE, MarineSource.OPEN_METEO_AIR_QUALITY.name(),
                Instant.now().minus(30, ChronoUnit.DAYS)));

        // A threshold in the future makes every row "expired" — only the prefix protects the real one.
        sweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(readingRepository.existsByMetricKeyAndStationKey("pm10", minted)).isFalse();
        assertThat(syncRepository.findAll()).noneMatch(sync -> sync.getId().getStationKey().equals(minted));
        assertThat(readingRepository.existsByMetricKeyAndStationKey("pm10", realStation)).isTrue();
        transactionTemplate.executeWithoutResult(_ -> readingRepository.deleteByStationKey(realStation));
    }

    private String mint(String body) throws Exception {
        String response = mockMvc.perform(post("/api/dev/marine-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("stationKey").asString();
    }
}

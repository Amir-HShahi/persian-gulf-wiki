package com.persiangulfwiki.core.marinedata;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Readings are seeded straight through the repository: the read side is what is under test,
// and exact values at exact times are not reachable through the providers. Every request is
// anonymous, which is also what proves the routes are public.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarineMetricsFlowIntegrationTests {

    private static final String SST = "sea_surface_temperature";
    private static final String BAA = "coral_bleaching_alert";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarineMetricReadingRepository readingRepository;

    @Autowired
    private MarineSourceSyncRepository syncRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Qualifier(MarineDataConfig.CACHE_MANAGER)
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Instant today;
    private Instant thisHour;

    @BeforeEach
    void reset() {
        readingRepository.deleteAll();
        syncRepository.deleteAll();
        cacheManager.getCache(MarineDataConfig.LATEST_CACHE).invalidate();
        cacheManager.getCache(MarineDataConfig.SERIES_CACHE).invalidate();
        thisHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        today = thisHour.truncatedTo(ChronoUnit.DAYS);
    }

    @Test
    void latestReturnsOneCardPerMetricAndStationWithSparklineAndSyncState() throws Exception {
        // The card is anchored on its latest reading, not on the clock, so fixed times of day
        // keep this independent of when it runs.
        Instant latestAt = today.plus(2, ChronoUnit.HOURS);
        for (int day = 1; day <= 6; day++) {
            seed(SST, "bushehr", today.minus(day, ChronoUnit.DAYS), "20");
        }
        seed(SST, "bushehr", today.plus(1, ChronoUnit.HOURS), "30");
        seed(SST, "bushehr", latestAt, "31");
        Instant synced = thisHour.plus(5, ChronoUnit.MINUTES);
        sync(MarineSource.OPEN_METEO_MARINE, "bushehr", synced);

        mockMvc.perform(get("/api/marine-metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].metricKey").value(SST))
                .andExpect(jsonPath("$.data[0].stationKey").value("bushehr"))
                .andExpect(jsonPath("$.data[0].value").value(31.0))
                .andExpect(jsonPath("$.data[0].unit").value("celsius"))
                .andExpect(jsonPath("$.data[0].observedAt").value(latestAt.toString()))
                // The 7 days before the latest reading average to (6 * 20 + 30) / 7 = 21.4286.
                .andExpect(jsonPath("$.data[0].baselineDelta").value(9.5714))
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.data[0].sparkline", hasSize(7)))
                .andExpect(jsonPath("$.data[0].sparkline[0].bucketStart").value(today.minus(6, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.data[0].sparkline[6].bucketStart").value(today.toString()))
                .andExpect(jsonPath("$.data[0].sparkline[6].value").value(30.5))
                .andExpect(jsonPath("$.data[0].lastSyncedAt").value(synced.toString()))
                .andExpect(jsonPath("$.data[0].staleAfter").value(synced.plus(2, ChronoUnit.HOURS).toString()))
                .andExpect(jsonPath("$.data[0].sourceName").value("Open-Meteo"))
                .andExpect(jsonPath("$.data[0].sourceLicense").value("CC BY 4.0"));
    }

    @Test
    void statusBandFollowsTheMetric() throws Exception {
        seed(BAA, "bushehr", today.minus(1, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS), "3");

        mockMvc.perform(get("/api/marine-metrics"))
                .andExpect(jsonPath("$.data[0].status").value("ALERT"))
                .andExpect(jsonPath("$.data[0].baselineDelta").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastSyncedAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].staleAfter").doesNotExist());
    }

    @Test
    void weekSeriesAveragesHourlyReadingsIntoDailyBuckets() throws Exception {
        seed(SST, "bushehr", today.minus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS), "20");
        seed(SST, "bushehr", today.minus(2, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), "22");
        seed(SST, "bushehr", today.minus(10, ChronoUnit.DAYS), "99");

        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "bushehr")
                        .param("range", "week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("WEEK"))
                .andExpect(jsonPath("$.data.unit").value("celsius"))
                // The reading 10 days ago is outside the window.
                .andExpect(jsonPath("$.data.points", hasSize(1)))
                .andExpect(jsonPath("$.data.points[0].bucketStart").value(today.minus(2, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.data.points[0].value").value(21.0));
    }

    @Test
    void monthSeriesUsesMaxForStressIndicators() throws Exception {
        Instant day = today.minus(20, ChronoUnit.DAYS);
        seed(BAA, "bushehr", day.plus(6, ChronoUnit.HOURS), "1");
        seed(BAA, "bushehr", day.plus(12, ChronoUnit.HOURS), "4");
        seed(BAA, "bushehr", today.minus(40, ChronoUnit.DAYS), "2");

        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", BAA, "bushehr")
                        .param("range", "MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points", hasSize(1)))
                .andExpect(jsonPath("$.data.points[0].value").value(4.0));
    }

    @Test
    void daySeriesBucketsByHourOverTheLast24Hours() throws Exception {
        seed(SST, "bushehr", thisHour, "25");
        seed(SST, "bushehr", thisHour.minus(23, ChronoUnit.HOURS), "24");
        seed(SST, "bushehr", thisHour.minus(24, ChronoUnit.HOURS), "99");

        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "bushehr")
                        .param("range", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points", hasSize(2)))
                .andExpect(jsonPath("$.data.points[0].bucketStart").value(thisHour.minus(23, ChronoUnit.HOURS).toString()))
                .andExpect(jsonPath("$.data.points[1].value").value(25.0));
    }

    @Test
    void seriesIsCachedAfterTheFirstRead() throws Exception {
        seed(SST, "bushehr", thisHour, "25");
        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "bushehr")
                        .param("range", "week"))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey("marine-series::sea_surface_temperature:bushehr:WEEK")).isTrue();

        // A reading written behind the cache's back is not visible until the next eviction.
        seed(SST, "bushehr", today.minus(3, ChronoUnit.DAYS), "10");
        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "bushehr")
                        .param("range", "WEEK"))
                .andExpect(jsonPath("$.data.points", hasSize(1)));
    }

    @Test
    void badInputsAreRejectedWithSpecificCodes() throws Exception {
        seed(SST, "bushehr", thisHour, "25");
        seed(BAA, "bushehr", today, "1");

        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", "sea_level", "bushehr"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MARINE_METRIC_NOT_FOUND"));
        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MARINE_STATION_NOT_FOUND"));
        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", SST, "bushehr")
                        .param("range", "YEAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MARINE_SERIES_RANGE"));
        mockMvc.perform(get("/api/marine-metrics/{metric}/stations/{station}/series", BAA, "bushehr")
                        .param("range", "DAY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MARINE_SERIES_RANGE"));

        // None of the failures left anything in the cache.
        assertThat(redisTemplate.keys("marine-series::*")).isEmpty();
    }

    @Test
    void onlyReadsArePublicAndOtherPublicReadsStillWork() throws Exception {
        // The new permit is GET-only: any other method still meets the main chain (CSRF first).
        mockMvc.perform(post("/api/marine-metrics"))
                .andExpect(status().isForbidden());
        // Adjacent public reads in the same matcher group are unaffected.
        mockMvc.perform(get("/api/subjects"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sources"))
                .andExpect(status().isOk());
        // And a protected route is still protected.
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    private void seed(String metricKey, String stationKey, Instant observedAt, String value) {
        transactionTemplate.executeWithoutResult(_ -> readingRepository.upsert(metricKey, stationKey, observedAt,
                new BigDecimal(value), MarineSource.OPEN_METEO_MARINE.name(), Instant.now()));
    }

    private void sync(MarineSource source, String stationKey, Instant lastSuccessAt) {
        syncRepository.save(MarineSourceSync.builder()
                .id(new MarineSourceSyncId(source.name(), stationKey))
                .lastAttemptAt(lastSuccessAt)
                .lastSuccessAt(lastSuccessAt)
                .build());
    }
}

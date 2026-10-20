package com.persiangulfwiki.core.marinedata;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.marinedata.entity.MarineMetricReading;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;
import com.persiangulfwiki.core.marinedata.repository.MarineMetricReadingRepository;
import com.persiangulfwiki.core.marinedata.repository.MarineSourceSyncRepository;
import com.persiangulfwiki.core.marinedata.service.MarineDataIngestService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The providers are stood in for by a real local HTTP server rather than a mocked client, so
// the URL building, the JSON parsing and the error path of each client are what is exercised.
// The RestClient is built once at startup, so the server must exist before the context does.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarineDataIngestFlowIntegrationTests {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.HOURS);
    private static final HttpServer PROVIDERS = startProviders();
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final AtomicInteger AIR_QUALITY_STATUS = new AtomicInteger(200);

    @Autowired
    private MarineDataIngestService ingestService;

    @Autowired
    private MarineMetricReadingRepository readingRepository;

    @Autowired
    private MarineSourceSyncRepository syncRepository;

    @Autowired
    @Qualifier(MarineDataConfig.CACHE_MANAGER)
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + PROVIDERS.getAddress().getPort();
        registry.add("app.marine-data.open-meteo-marine.base-url", () -> baseUrl);
        registry.add("app.marine-data.open-meteo-air-quality.base-url", () -> baseUrl);
        registry.add("app.marine-data.noaa-erddap.base-url", () -> baseUrl);
    }

    @AfterAll
    static void stopProviders() {
        PROVIDERS.stop(0);
    }

    @BeforeEach
    void reset() {
        readingRepository.deleteAll();
        syncRepository.deleteAll();
        cacheManager.getCache(MarineDataConfig.LATEST_CACHE).invalidate();
        cacheManager.getCache(MarineDataConfig.SERIES_CACHE).invalidate();
        HITS.clear();
        AIR_QUALITY_STATUS.set(200);
    }

    @Test
    void ingestStoresPastValuesOnlyAndDropsForecastsAndNulls() {
        ingestService.ingestDue(NOW);

        // Marine stub: two past hours with values, one past hour null, one forecast hour.
        List<MarineMetricReading> sst = readings("sea_surface_temperature", "bushehr");
        assertThat(sst).extracting(MarineMetricReading::getObservedAt)
                .containsExactly(NOW.minus(2, ChronoUnit.HOURS), NOW);
        assertThat(sst.get(1).getValue()).isEqualByComparingTo("31.5");
        assertThat(readings("sea_surface_temperature", "asaluyeh")).hasSize(2);

        // Every air-quality variable is parsed from the same response.
        assertThat(readings("air_quality_index", "asaluyeh")).hasSize(2);
        assertThat(readings("dust", "asaluyeh")).hasSize(2);

        // ERDDAP: one dataset per variable, daily rows, a null (land) cell dropped.
        assertThat(readings("coral_bleaching_alert", "bushehr")).hasSize(2);
        assertThat(readings("degree_heating_weeks", "bushehr")).hasSize(2);
        assertThat(readings("sea_surface_temperature_anomaly", "bushehr")).hasSize(1);

        assertThat(sync(MarineSource.OPEN_METEO_MARINE, "bushehr").getLastSuccessAt()).isEqualTo(NOW);
    }

    @Test
    void reingestingTheSameWindowUpdatesInsteadOfDuplicating() {
        ingestService.ingestDue(NOW);
        long afterFirst = readingRepository.count();

        // Forgetting the sync rows makes every source due again at the same moment, so it
        // re-sends exactly the same rows. (A later "now" would also admit the stub's forecast
        // hour, which is a genuinely new row.)
        syncRepository.deleteAll();
        ingestService.ingestDue(NOW);

        assertThat(readingRepository.count()).isEqualTo(afterFirst);
        assertThat(HITS.get("/v1/marine").get()).isEqualTo(4);
    }

    @Test
    void sourcesAreOnlyCalledOnceTheirIntervalHasPassed() {
        ingestService.ingestDue(NOW);
        ingestService.ingestDue(NOW.plus(5, ChronoUnit.MINUTES));
        assertThat(HITS.get("/v1/marine").get()).isEqualTo(2);

        // One hour later the hourly sources are due; the 6-hourly ERDDAP source is not.
        int erddapCalls = HITS.get("/griddap/noaacrwdhwDaily.json").get();
        ingestService.ingestDue(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(HITS.get("/v1/marine").get()).isEqualTo(4);
        assertThat(HITS.get("/griddap/noaacrwdhwDaily.json").get()).isEqualTo(erddapCalls);
    }

    @Test
    void failingProviderLeavesOthersIngestedAndKeepsItsLastSyncedAt() {
        ingestService.ingestDue(NOW);
        assertThat(sync(MarineSource.OPEN_METEO_AIR_QUALITY, "asaluyeh").getLastSuccessAt()).isEqualTo(NOW);

        AIR_QUALITY_STATUS.set(500);
        Instant later = NOW.plus(1, ChronoUnit.HOURS);
        ingestService.ingestDue(later);

        MarineSourceSync failed = sync(MarineSource.OPEN_METEO_AIR_QUALITY, "asaluyeh");
        assertThat(failed.getLastAttemptAt()).isEqualTo(later);
        assertThat(failed.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(failed.getLastError()).contains("500");
        // The marine source ran in the same tick and was unaffected.
        assertThat(sync(MarineSource.OPEN_METEO_MARINE, "bushehr").getLastSuccessAt()).isEqualTo(later);
        assertThat(sync(MarineSource.OPEN_METEO_MARINE, "bushehr").getLastError()).isNull();
    }

    @Test
    void cacheIsFilledByAReadAndClearedByAnIngest() throws Exception {
        ingestService.ingestDue(NOW);
        mockMvc.perform(get("/api/marine-metrics")).andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("marine-latest::all")).isTrue();

        ingestService.ingestDue(NOW.plus(1, ChronoUnit.HOURS));

        assertThat(redisTemplate.hasKey("marine-latest::all")).isFalse();
    }

    private List<MarineMetricReading> readings(String metricKey, String stationKey) {
        return readingRepository.findAll().stream()
                .filter(r -> r.getMetricKey().equals(metricKey) && r.getStationKey().equals(stationKey))
                .sorted((a, b) -> a.getObservedAt().compareTo(b.getObservedAt()))
                .toList();
    }

    private MarineSourceSync sync(MarineSource source, String stationKey) {
        return syncRepository.findById(new MarineSourceSyncId(source.name(), stationKey)).orElseThrow();
    }

    private static HttpServer startProviders() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", MarineDataIngestFlowIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        HITS.computeIfAbsent(path, _ -> new AtomicInteger()).incrementAndGet();
        int status = 200;
        String body;
        if (path.equals("/v1/marine")) {
            body = hourly("sea_surface_temperature", "30.9", "null", "31.5", "32.0");
        } else if (path.equals("/v1/air-quality")) {
            status = AIR_QUALITY_STATUS.get();
            body = status != 200 ? "{\"error\":true}" : hourly("european_aqi", "35", "null", "42", "50")
                    .replace("}}", ",\"pm2_5\":[1,null,2,3],\"pm10\":[1,null,2,3],\"sulphur_dioxide\":[1,null,2,3],"
                            + "\"nitrogen_dioxide\":[1,null,2,3],\"dust\":[1,null,2,3]}}");
        } else if (path.startsWith("/griddap/")) {
            String day1 = NOW.truncatedTo(ChronoUnit.DAYS).minus(3, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS).toString();
            String day2 = NOW.truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS).toString();
            String second = path.contains("sstanomaly") ? "null" : "2";
            body = "{\"table\":{\"rows\":[[\"" + day1 + "\",28.925,50.575,1],[\"" + day2 + "\",28.925,50.575," + second + "]]}}";
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // Four hours: two ago, one ago, now, and one in the future (a forecast hour).
    private static String hourly(String variable, String... values) {
        long base = NOW.minus(2, ChronoUnit.HOURS).getEpochSecond();
        return "{\"hourly\":{\"time\":[" + base + "," + (base + 3600) + "," + (base + 7200) + "," + (base + 10800)
                + "],\"" + variable + "\":[" + String.join(",", values) + "]}}";
    }
}

package com.persiangulfwiki.core.marinedata;

import com.persiangulfwiki.core.marinedata.dto.MarineMetricResponse;
import com.persiangulfwiki.core.marinedata.dto.MarineSeriesResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

// The read side's Redis cache and the ingest side's outbound HTTP client.
//
// Both are built by hand: this Boot build ships neither the cache nor the RestClient
// auto-configuration module, and the two beans below are all either would have provided.
@Configuration
@EnableCaching
@EnableConfigurationProperties(MarineDataProperties.class)
public class MarineDataConfig implements CachingConfigurer {

    public static final String CACHE_MANAGER = "marineCacheManager";
    public static final String LATEST_CACHE = "marine-latest";
    public static final String SERIES_CACHE = "marine-series";

    // Both caches are cleared after every ingest that wrote something, so these TTLs are only
    // a safety net for the case where ingest stops entirely.
    private static final Duration LATEST_TTL = Duration.ofHours(1);
    private static final Duration SERIES_TTL = Duration.ofDays(1);

    // Each cache is serialized as its one concrete type, with no default typing. A
    // polymorphic serializer would write class names into Redis and trust them on the way
    // back, which turns write access to Redis into a deserialization gadget.
    @Bean(CACHE_MANAGER)
    CacheManager marineCacheManager(RedisConnectionFactory connectionFactory) {
        JsonMapper mapper = JsonMapper.builder().build();
        JavaType latestType = mapper.getTypeFactory().constructCollectionType(List.class, MarineMetricResponse.class);
        JavaType seriesType = mapper.getTypeFactory().constructType(MarineSeriesResponse.class);
        // SCAN rather than the default KEYS for invalidation: KEYS blocks the Redis instance the
        // media pipeline's streams share.
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, BatchStrategies.scan(100));
        return RedisCacheManager.builder(writer)
                .withCacheConfiguration(LATEST_CACHE, cacheConfig(LATEST_TTL, new JacksonJsonRedisSerializer<>(mapper, latestType)))
                .withCacheConfiguration(SERIES_CACHE, cacheConfig(SERIES_TTL, new JacksonJsonRedisSerializer<>(mapper, seriesType)))
                .disableCreateOnMissingCache()
                .build();
    }

    // Redis is a cache here, not the source of truth: if it is unreachable the read falls
    // through to Postgres and the failure is logged, instead of the homepage cards erroring.
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    // Redirects are not followed (the JDK client's default): every base URL is a fixed
    // provider endpoint, and a redirect elsewhere is a reason to fail, not to follow.
    @Bean
    RestClient marineDataRestClient(MarineDataProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static RedisCacheConfiguration cacheConfig(Duration ttl, JacksonJsonRedisSerializer<?> serializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }
}

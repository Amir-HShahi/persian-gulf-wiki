package com.persiangulfwiki.core.search.service;

import com.persiangulfwiki.core.search.exception.SearchRateLimitedException;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

// The only brake on the two public search routes, which anyone can call without an account and
// each of which runs a full-text query: a fixed window per client address bucket
// (web.ClientAddresses) in Redis, the same shape as MediaUploadLimitService's. Suggestions get a
// far larger budget than full searches because a search box asks for them on every keystroke.
// Every number is config (app.search.*).
@Service
@RequiredArgsConstructor
public class SearchRateLimitService {

    private static final String SEARCH_KEY_PREFIX = "search:results:";
    private static final String SUGGEST_KEY_PREFIX = "search:suggestions:";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.search.searches-per-window}")
    private final long searchesPerWindow;

    @Value("${app.search.suggestions-per-window}")
    private final long suggestionsPerWindow;

    @Value("${app.search.rate-window-seconds}")
    private final long windowSeconds;

    public void requireWithinSearchLimit(String clientBucket) {
        requireWithinRate(SEARCH_KEY_PREFIX, clientBucket, searchesPerWindow);
    }

    public void requireWithinSuggestionLimit(String clientBucket) {
        requireWithinRate(SUGGEST_KEY_PREFIX, clientBucket, suggestionsPerWindow);
    }

    private void requireWithinRate(String keyPrefix, String clientBucket, long limit) {
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        String key = keyPrefix + clientBucket + ":" + windowStart;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }
        if (count != null && count > limit) {
            throw new SearchRateLimitedException(windowStart + windowSeconds - now);
        }
    }
}

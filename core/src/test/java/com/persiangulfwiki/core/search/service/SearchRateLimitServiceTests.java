package com.persiangulfwiki.core.search.service;

import com.persiangulfwiki.core.search.exception.SearchRateLimitedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchRateLimitServiceTests {

    private static final long SEARCHES = 3;
    private static final long SUGGESTIONS = 10;
    private static final long WINDOW_SECONDS = 60;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SearchRateLimitService searchRateLimitService;

    @BeforeEach
    void setUp() {
        searchRateLimitService = new SearchRateLimitService(redisTemplate, SEARCHES, SUGGESTIONS, WINDOW_SECONDS);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // The key is the bucket's fixed window: the epoch second the current window started.
    private static long windowStartNow() {
        long now = Instant.now().getEpochSecond();
        return now - (now % WINDOW_SECONDS);
    }

    private String countedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(key.capture());
        return key.getValue();
    }

    @Test
    void theFirstSearchInAWindowStartsItsCounterAndExpiresItWithTheWindow() {
        long before = windowStartNow();
        when(valueOperations.increment(anyString())).thenReturn(1L);

        searchRateLimitService.requireWithinSearchLimit("v4:203.0.113.9");

        String key = countedKey();
        long after = windowStartNow();
        assertThat(key).startsWith("search:results:v4:203.0.113.9:");
        long windowStart = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
        assertThat(windowStart).isBetween(before, after);
        assertThat(windowStart % WINDOW_SECONDS).isZero();
        verify(redisTemplate).expire(key, Duration.ofSeconds(WINDOW_SECONDS));
    }

    // Only the first increment sets the expiry; resetting it on every call would let a steady
    // trickle keep a window alive for ever.
    @Test
    void laterSearchesInTheWindowDoNotTouchTheExpiry() {
        when(valueOperations.increment(anyString())).thenReturn(2L);

        searchRateLimitService.requireWithinSearchLimit("v4:203.0.113.9");

        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ofSeconds(WINDOW_SECONDS)));
    }

    @Test
    void searchesUpToTheLimitPassAndTheNextIsRefusedWithTheSecondsLeftInTheWindow() {
        when(valueOperations.increment(anyString())).thenReturn(SEARCHES);
        assertThatNoException().isThrownBy(() -> searchRateLimitService.requireWithinSearchLimit("v4:a"));

        when(valueOperations.increment(anyString())).thenReturn(SEARCHES + 1);
        long before = Instant.now().getEpochSecond();
        SearchRateLimitedException refused = catchThrowableOfType(SearchRateLimitedException.class,
                () -> searchRateLimitService.requireWithinSearchLimit("v4:a"));
        long after = Instant.now().getEpochSecond();

        assertThat(refused).isNotNull();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).increment(keys.capture());
        String key = keys.getAllValues().getLast();
        long windowEnd = Long.parseLong(key.substring(key.lastIndexOf(':') + 1)) + WINDOW_SECONDS;
        assertThat(refused.getRetryAfterSeconds()).isBetween(windowEnd - after, windowEnd - before);
        assertThat(refused.getRetryAfterSeconds()).isBetween(1L, WINDOW_SECONDS);
    }

    // Suggestions have their own counter and their own, larger budget: a search box asks for one
    // on every keystroke, which must not use up the client's searches.
    @Test
    void suggestionsAreCountedSeparatelyAgainstTheirOwnLimit() {
        when(valueOperations.increment(anyString())).thenReturn(SUGGESTIONS);

        assertThatNoException().isThrownBy(() -> searchRateLimitService.requireWithinSuggestionLimit("v6:20010db8"));
        assertThat(countedKey()).startsWith("search:suggestions:v6:20010db8:");

        when(valueOperations.increment(anyString())).thenReturn(SUGGESTIONS + 1);
        assertThat(catchThrowableOfType(SearchRateLimitedException.class,
                () -> searchRateLimitService.requireWithinSuggestionLimit("v6:20010db8"))).isNotNull();
    }

    // A Redis that answers nothing (a pipeline or transaction) must not lock everyone out.
    @Test
    void aMissingCountLetsTheRequestThrough() {
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertThatNoException().isThrownBy(() -> searchRateLimitService.requireWithinSearchLimit("v4:a"));
        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ofSeconds(WINDOW_SECONDS)));
    }
}

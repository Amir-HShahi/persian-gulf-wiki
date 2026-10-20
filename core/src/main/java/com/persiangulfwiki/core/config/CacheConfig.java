package com.persiangulfwiki.core.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// In-process caches, from spring-context alone: no cache library, and nothing to share, since
// production runs a single app instance. An entry has no expiry; every cache here is cleared by
// the writes that change what it holds (see SubjectInfoboxCacheService).
//
// The names are fixed, so a typo in a cache name fails instead of quietly creating a cache nothing
// evicts. The transaction-aware wrapper defers a clear or put made inside a transaction until it
// commits, so an eviction lands after the new state is visible rather than before it.
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SUBJECT_INFOBOX = "subjectInfobox";

    @Bean
    public CacheManager cacheManager() {
        return new TransactionAwareCacheManagerProxy(new ConcurrentMapCacheManager(SUBJECT_INFOBOX));
    }
}

package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.config.CacheConfig;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

// Clears every cached infobox. Called by each write that changes something an infobox shows: a
// subject revision's approval, an expert's mark, a label or any other vocabulary write, and an
// article translation's first approval (which can change a subject's main article and so its
// slug).
//
// Wholesale on purpose. An infobox shows more than its own subject's live revision -- the names
// and slugs of the subjects it refers to, the verification state, the vocabulary's labels and
// order -- so a targeted eviction would have to know every subject that mentions whatever changed.
// These writes are rare next to reads, and a cold entry costs one infobox's queries.
//
// Inside a transaction the clear runs after commit (see CacheConfig).
@Service
public class SubjectInfoboxCacheService {

    @CacheEvict(cacheNames = CacheConfig.SUBJECT_INFOBOX, allEntries = true)
    public void evictAll() {
        // The annotation does the work.
    }
}

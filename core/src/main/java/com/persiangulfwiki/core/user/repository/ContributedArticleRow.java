package com.persiangulfwiki.core.user.repository;

import java.time.Instant;
import java.util.UUID;

// One article a user contributed to; types is a comma-separated set of ContributionType names.
public interface ContributedArticleRow {

    UUID getArticleId();

    String getTypes();

    Instant getLastContributedAt();
}

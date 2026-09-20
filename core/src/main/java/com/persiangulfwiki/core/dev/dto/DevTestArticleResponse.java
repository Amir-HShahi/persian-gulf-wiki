package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.article.entity.RevisionStatus;

import java.util.UUID;

// Carries enough of the minted tree -- the article, its canonical translation, and that
// translation's first revision -- that a test can drive the real /api/articles endpoints
// afterward (e.g. PATCH .../revisions/{revisionId}) without a second lookup.
public record DevTestArticleResponse(
        UUID articleId, UUID translationId, UUID revisionId, String language, String slug, RevisionStatus status) {
}

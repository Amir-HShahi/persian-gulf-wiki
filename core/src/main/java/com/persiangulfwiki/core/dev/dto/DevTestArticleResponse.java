package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import com.persiangulfwiki.core.article.entity.RevisionStatus;

import java.util.UUID;

// Carries enough of the minted tree -- the article, its canonical translation, and that
// translation's first revision -- that a test can drive the real /api/articles endpoints
// afterward (e.g. PATCH .../revisions/{revisionId}) without a second lookup. article shows approved
// text only, so its text fields are null unless status is APPROVED; revisionId is the minted
// revision either way.
public record DevTestArticleResponse(
        ArticleCardResponse article, UUID translationId, UUID revisionId, String language, String slug,
        RevisionStatus status, boolean isMain) {
}

package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.entity.TranslationState;

import java.util.UUID;

// Every field optional, so the common case is a bare `{}` (or no body at all): a GENERIC
// article in "fa" with a generated slug, a DRAFT first revision, and an auto-minted author.
//
// entityType is only read when subjectId is null. When subjectId is set, entityType is
// always derived from the bound Subject's kind and any value sent here is silently ignored
// -- unlike CreateArticleRequest, this endpoint does not reject the combination, since it
// bypasses ArticleService.resolveEntityType's cross-field check entirely. When subjectId is
// null, the real create path only ever accepts GENERIC (EntityTypeNotDerivableException
// otherwise) -- this endpoint deliberately allows any value, including STRAIT, because
// EntityType.STRAIT's own comment says it "remains selectable directly for a subject-less
// article" even though Phase 1 never gave it a matching SubjectKind to derive it from. That
// is exactly the state a Phase 5 fixture needs and the real endpoint cannot produce.
//
// revisionStatus is the other point of this endpoint: it can mint a revision at any status,
// including APPROVED with no moderation history at all -- a state the real submit/review
// flow can never produce, and exactly what Phase 5's ExpertReview tests need as a fixture.
//
// authorUserId is nullable; see DevTestArticleController.resolveAuthorUserId for what
// happens when it's omitted.
public record DevTestArticleRequest(
        UUID subjectId,
        EntityType entityType,
        String canonicalLanguage,
        String slug,
        RevisionStatus revisionStatus,
        String title,
        String summary,
        UUID authorUserId,
        TranslationState translationState) {
}

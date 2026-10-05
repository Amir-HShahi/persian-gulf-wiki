package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.entity.TranslationState;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

// Every field optional, so the common case is a bare `{}` (or no body at all): a subject-less
// article in "fa" with a generated slug, a DRAFT first revision, and an auto-minted author.
//
// There is no kind field, as on CreateArticleRequest: an article's subjectKind is read off
// subjectId's subject, so the only way to mint an article of a given kind is to bind it to a
// subject of that kind (POST /api/dev/test-subjects mints one).
//
// revisionStatus is the other point of this endpoint: it can mint a revision at any status,
// including APPROVED with no moderation history at all -- a state the real submit/review
// flow can never produce, and exactly what Phase 5's ExpertReview tests need as a fixture.
//
// authorUserId is nullable; see DevTestArticleController.resolveAuthorUserId for what
// happens when it's omitted.
//
// isMain null follows the real rule (an APPROVED article becomes main when its subject has none);
// false never makes it main, so "published but not main" is reachable on a subject with no main.
// body is stored as given, unvalidated, so a subject link to a missing subject is reachable too.
public record DevTestArticleRequest(
        UUID subjectId,
        String canonicalLanguage,
        String slug,
        RevisionStatus revisionStatus,
        String title,
        String summary,
        UUID authorUserId,
        TranslationState translationState,
        Boolean isMain,
        JsonNode body) {
}

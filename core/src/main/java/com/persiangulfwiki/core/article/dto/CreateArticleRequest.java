package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.EntityType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

// Creates the article and its first (canonical-language) translation and revision together
// in one call -- see ArticleService.create. That's why this carries translation/revision
// fields (slug, title, body, summary) alongside the article-level ones: there is no
// standalone "create an empty article" step, since an article with no translation would be
// unaddressable (nothing to GET by slug or language).
//
// entityType is deliberately NOT @NotNull: it's required only when subjectId is null (the
// client must then pass GENERIC explicitly), and forbidden when subjectId is set (it's
// derived from the bound Subject's kind instead). That's a cross-field rule, so
// ArticleService enforces it and rejects a violation with EntityTypeNotDerivableException ->
// 400, the same reasoning SubjectService.rejectFieldsForeignToKind uses for kind-specific
// fields.
public record CreateArticleRequest(
        UUID subjectId,
        EntityType entityType,
        @NotBlank(message = "{validation.language.required}") @Size(max = 20, message = "{validation.language.tooLong}") String canonicalLanguage,
        @NotBlank(message = "{validation.slug.required}") @Size(max = 200, message = "{validation.slug.tooLong}") String slug,
        @NotBlank(message = "{validation.articleTitle.required}") @Size(max = 500, message = "{validation.articleTitle.tooLong}") String title,
        @NotNull(message = "{validation.articleBody.required}") JsonNode body,
        @Size(max = 2000, message = "{validation.articleSummary.tooLong}") String summary) {
}

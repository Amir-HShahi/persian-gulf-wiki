package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

// Creates the article and its first (canonical-language) translation and revision together
// in one call -- see ArticleService.create. That's why this carries translation/revision
// fields (slug, title, body, summary) alongside the article-level ones: there is no
// standalone "create an empty article" step, since an article with no translation would be
// unaddressable (nothing to GET by slug or language).
//
// There is no kind field: the article's subjectKind is read off subjectId's subject, and an
// article with no subject has none.
public record CreateArticleRequest(
        UUID subjectId,
        @NotBlank(message = "{validation.language.required}") @Size(max = 20, message = "{validation.language.tooLong}") String canonicalLanguage,
        @NotBlank(message = "{validation.slug.required}") @Size(max = 200, message = "{validation.slug.tooLong}") String slug,
        @NotBlank(message = "{validation.articleTitle.required}") @Size(max = 500, message = "{validation.articleTitle.tooLong}") String title,
        @NotNull(message = "{validation.articleBody.required}") JsonNode body,
        @Size(max = 2000, message = "{validation.articleSummary.tooLong}") String summary,
        @Schema(description = "Ids of the topics the article is about (see `GET /api/topics`), at most 5. Optional. "
                + "They apply to every translation of the article.")
        @Size(max = 5, message = "{validation.topicIds.tooMany}")
                Set<@NotNull(message = "{validation.topicIds.notFound}") UUID> topicIds) {
}

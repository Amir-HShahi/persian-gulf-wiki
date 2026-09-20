package com.persiangulfwiki.core.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

// Adds a new language to an existing article, creating that language's first DRAFT revision
// in the same call -- see ArticleTranslationService.addTranslation. Rejected with
// DuplicateTranslationLanguageException -> 409 if the article already has a translation for
// this language.
public record CreateTranslationRequest(
        @NotBlank(message = "{validation.language.required}") @Size(max = 20, message = "{validation.language.tooLong}") String language,
        @NotBlank(message = "{validation.slug.required}") @Size(max = 200, message = "{validation.slug.tooLong}") String slug,
        @NotBlank(message = "{validation.articleTitle.required}") @Size(max = 500, message = "{validation.articleTitle.tooLong}") String title,
        @NotNull(message = "{validation.articleBody.required}") JsonNode body,
        @Size(max = 2000, message = "{validation.articleSummary.tooLong}") String summary) {
}

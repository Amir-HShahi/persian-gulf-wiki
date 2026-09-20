package com.persiangulfwiki.core.article.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

// Replaces title/body/summary on an existing DRAFT or CHANGES_REQUESTED revision in place --
// not a merge-patch. title and body are required on every call (the revision can't be left
// without them); summary is optional and nullable, same as CreateArticleRequest/
// CreateTranslationRequest, so omitting it clears it. See ArticleRevisionService.update for
// the editability rule (RevisionNotEditableException) and the authorship check
// (NotRevisionAuthorException).
public record UpdateRevisionRequest(
        @NotBlank(message = "{validation.articleTitle.required}") @Size(max = 500, message = "{validation.articleTitle.tooLong}") String title,
        @NotNull(message = "{validation.articleBody.required}") JsonNode body,
        @Size(max = 2000, message = "{validation.articleSummary.tooLong}") String summary) {
}

package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

// Replaces title/body/summary on an existing DRAFT or CHANGES_REQUESTED revision in place --
// not a merge-patch. title and body are required on every call (the revision can't be left
// without them); summary is optional and nullable, same as CreateArticleRequest/
// CreateTranslationRequest, so omitting it clears it. See ArticleRevisionService.update for
// the editability rule (RevisionNotEditableException) and the authorship check
// (NotRevisionAuthorException).
//
// topicIds is the one exception to the replace-everything rule: null keeps the revision's topics, so
// a text-only edit can't drop them; a set (possibly empty) replaces them. A Set, so a repeated id
// counts once against the cap.
public record UpdateRevisionRequest(
        @NotBlank(message = "{validation.articleTitle.required}") @Size(max = 500, message = "{validation.articleTitle.tooLong}") String title,
        @NotNull(message = "{validation.articleBody.required}") JsonNode body,
        @Size(max = 2000, message = "{validation.articleSummary.tooLong}") String summary,
        @Schema(description = "Ids of the topics the article is about (see `GET /api/topics`), at most 5. Leave it "
                + "out to keep the revision's current topics; send `[]` to remove them all. Only a revision in the "
                + "article's original language can have topics; on any other a non-empty list is rejected.")
        @Size(max = 5, message = "{validation.topicIds.tooMany}")
                Set<@NotNull(message = "{validation.topicIds.notFound}") UUID> topicIds) {
}

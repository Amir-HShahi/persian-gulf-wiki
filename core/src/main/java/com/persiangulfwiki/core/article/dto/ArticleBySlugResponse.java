package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// GET /api/articles/by-slug/{slug}: everything the reader page needs, read off the slug's
// translation and its approved revision only.
public record ArticleBySlugResponse(
        UUID id,
        @Schema(description = "The subject this article is about; null when it has none. The subject may have been "
                + "deleted since -- the article is still served.", nullable = true)
        @Nullable UUID subjectId,
        @Schema(description = "The kind of the subject this article is about; null when the article has no subject.\n\n"
                + SubjectKind.VALUES_DOC, nullable = true)
        @Nullable SubjectKind subjectKind,
        @Schema(description = "Whether this is the subject's main article, whose slug is the subject's page.")
        boolean isMain,
        String canonicalLanguage,
        Instant createdAt,
        Instant updatedAt,
        PublishedTranslationResponse translation,
        PublishedRevisionResponse revision,
        @Schema(description = "Subject id to what a subject link in `revision.body` shows: the subject's name in the "
                + "reader's language (from `Accept-Language`: fa, en or ar; anything else gets fa) and its page's "
                + "slug. A linked subject that no longer exists or has been deleted is absent; show the link as "
                + "plain text.")
        Map<UUID, SubjectLinkResponse> subjectLinks,
        @Schema(description = "Ids of the topics the article is about (see `GET /api/topics`), the same for every "
                + "translation. Empty until the text in the article's original language has been approved.")
        List<UUID> topicIds,
        @Schema(description = "The article's other published languages, for a language switcher. Translations with "
                + "nothing approved yet are left out.")
        List<TranslationSlugResponse> otherTranslations) {
}

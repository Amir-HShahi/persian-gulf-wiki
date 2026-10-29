package com.persiangulfwiki.core.search.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// Every text field is plain text except snippet, which the index builds as escaped HTML.
public record SearchHitResponse(
        UUID translationId,
        UUID articleId,

        @Schema(description = "The translation's language.", example = "fa")
        String language,

        @Schema(description = "The kind of subject the article is about. Null for an article with no subject.\n\n"
                + SubjectKind.VALUES_DOC, nullable = true)
        @Nullable SubjectKind subjectKind,

        @Schema(description = "The translation's slug, for linking to the article.", example = "kish-island")
        String slug,

        @Schema(description = "The article's title in this language. Plain text -- escape it.", example = "جزیره کیش")
        String title,

        @Schema(description = "When the article was found by another of its subject's names (another language's "
                + "name, an alias, or the name of a subject merged into it) rather than by its title: that name, for "
                + "showing e.g. \"Qays → Kish Island\". Null when the title matched. Plain text -- escape it.",
                nullable = true, example = "Qays")
        @Nullable String matchedName,

        @Schema(description = "An excerpt around the matches, as HTML: the text is already escaped and each match is "
                + "wrapped in `<mark>`, so it is safe to insert as HTML as it is. Do not escape it again (the marks "
                + "would show as text), and do not build HTML from any other field the same way. Empty when there "
                + "is nothing to excerpt.", example = "… در <mark>تنگه</mark> <mark>هرمز</mark> …")
        String snippet) {
}

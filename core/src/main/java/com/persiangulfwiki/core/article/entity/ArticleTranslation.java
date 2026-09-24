package com.persiangulfwiki.core.article.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

// One language's copy of an article. articleId, currentRevisionId and sourceRevisionId are
// plain UUID columns rather than JPA associations: currentRevisionId/sourceRevisionId form a
// reference cycle with ArticleRevision.translationId (a translation points at its current
// revision; a revision belongs to a translation) that V16 only resolves at the SQL level via
// a deferred ALTER TABLE, and mapping that cycle as bidirectional @ManyToOne/@OneToOne
// associations would buy nothing here -- every read of "the current revision" already goes
// through ArticleRevisionService via a repository lookup.
@Entity
@Table(name = "article_translations")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ArticleTranslation extends AuditableEntity {

    @Column(name = "article_id", nullable = false)
    private UUID articleId;

    // BCP-47 tag, e.g. "fa", "en", "ar".
    @Column(nullable = false, length = 20)
    private String language;

    // Globally unique (uq_article_translations_slug in V16), not scoped per article or
    // language -- see that constraint's comment for the URL-shape reasoning.
    @Column(nullable = false, length = 200)
    private String slug;

    // The revision readers are served for this language -- null until one has actually been
    // approved, which is the normal state of a translation whose first draft is still being
    // written or reviewed. Callers must handle null; it is not a transient window.
    //
    // Written in exactly one place, ArticleRevisionService.applyModerationOutcome, and only on
    // a moderator's APPROVE. Creation paths deliberately leave it alone: pointing it at the
    // DRAFT they had just inserted (which is what they originally did) meant unreviewed
    // content was served as this translation's live content from the moment the article
    // existed, and left the approval step with nothing to do.
    @Column(name = "current_revision_id")
    private UUID currentRevisionId;

    // Null for the canonical translation (nothing to sync against) and for any translation
    // created before its source had a current revision yet. See
    // ArticleTranslationService.addTranslation for how it's set.
    @Column(name = "source_revision_id")
    private UUID sourceRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "translation_state", nullable = false, length = 20)
    private TranslationState translationState;
}

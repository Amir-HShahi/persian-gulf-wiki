package com.persiangulfwiki.core.article.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

// The wiki entry. Carries identity, the subject it is about and its canonical language --
// the prose itself lives one level down in ArticleTranslation/ArticleRevision, same reasoning
// as Subject carrying no name (see V15/Subject.java): the mutable, per-language content would
// make this row a moving target if it lived here instead.
//
// subjectId and createdByUserId are plain UUID columns rather than @ManyToOne associations,
// matching Source.createdByUserId (source/entity/Source.java): both point at an entity in a
// different feature package, and ArticleService already needs the referenced row's own
// repository (SubjectRepository, to read the subject's kind for subjectKind) rather than a
// lazy JPA association across a package boundary.
//
// There is no kind column here: an article's subjectKind is always read off its subject, so
// the two can never disagree, and an article with no subject has no kind at all (V20).
@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Article extends AuditableEntity {

    @Column(name = "subject_id")
    private UUID subjectId;

    // BCP-47 tag of this article's source-of-truth translation. See the column comment in
    // V16 for why this isn't a foreign key to article_translations.
    @Column(name = "canonical_language", nullable = false, length = 20)
    private String canonicalLanguage;

    // Null once the creating account is deleted (ON DELETE SET NULL in V16) -- an article
    // outlives the contributor who started it, same as Source.createdByUserId.
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    // The subject's main article, whose slug is the subject's page URL (V28). Only
    // SubjectArticleService writes it, under the subject row lock.
    @Column(name = "is_main", nullable = false)
    private boolean isMain;

    // The translation the others are translated from (V35); null until one is chosen. Not the
    // same as canonicalLanguage, the language the article was created in.
    @Column(name = "source_translation_id")
    private UUID sourceTranslationId;

    // Set only by the dev-profile fixture endpoint; null on every row a real caller creates.
    @Column(name = "dev_marker", length = 40)
    private String devMarker;
}

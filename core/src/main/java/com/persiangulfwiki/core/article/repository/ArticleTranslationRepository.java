package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.EntityType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleTranslationRepository extends JpaRepository<ArticleTranslation, UUID> {

    Optional<ArticleTranslation> findByArticleIdAndLanguage(UUID articleId, String language);

    Optional<ArticleTranslation> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<ArticleTranslation> findByArticleId(UUID articleId);

    // Translations in one language that have an approved revision, filtered by their article's
    // subject/entityType (either filter null = not applied). Ordered newest article first, with
    // id as tiebreak so paging is stable.
    @Query(value = """
            select t from ArticleTranslation t join Article a on a.id = t.articleId
            where t.language = :language and t.currentRevisionId is not null
              and (:subjectId is null or a.subjectId = :subjectId)
              and (:entityType is null or a.entityType = :entityType)
            order by a.createdAt desc, a.id""",
            countQuery = """
            select count(t) from ArticleTranslation t join Article a on a.id = t.articleId
            where t.language = :language and t.currentRevisionId is not null
              and (:subjectId is null or a.subjectId = :subjectId)
              and (:entityType is null or a.entityType = :entityType)""")
    Page<ArticleTranslation> findPublishedByLanguage(@Param("language") String language,
            @Param("subjectId") UUID subjectId, @Param("entityType") EntityType entityType, Pageable pageable);
}

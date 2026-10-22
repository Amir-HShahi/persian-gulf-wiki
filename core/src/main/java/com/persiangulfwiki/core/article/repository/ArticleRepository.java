package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.Article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    Page<Article> findBySubjectId(UUID subjectId, Pageable pageable);

    boolean existsBySubjectId(UUID subjectId);

    @Query("select a from Article a where a.subjectId = :subjectId and a.isMain = true")
    Optional<Article> findMainBySubjectId(@Param("subjectId") UUID subjectId);

    // A subject's articles that have an approved translation: the main one first, then oldest first,
    // with id as tiebreak so paging is stable.
    @Query("""
            select a from Article a
            where a.subjectId = :subjectId
              and exists (select 1 from ArticleTranslation t
                          where t.articleId = a.id and t.currentRevisionId is not null)
            order by a.isMain desc, a.createdAt, a.id""")
    List<Article> findPublishedBySubjectId(@Param("subjectId") UUID subjectId, Pageable pageable);

    // Makes main, for each subject left without a main article, its oldest article with an approved
    // translation -- V28's backfill rule. Returns the number of subjects given one.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update articles set is_main = true
            where id in (select distinct on (a.subject_id) a.id
                         from articles a
                         where a.subject_id is not null
                           and not exists (select 1 from articles m
                                           where m.subject_id = a.subject_id and m.is_main)
                           and exists (select 1 from article_translations t
                                       where t.article_id = a.id and t.current_revision_id is not null)
                         order by a.subject_id, a.created_at, a.id)""", nativeQuery = true)
    int promoteMainArticlesWhereMissing();

    // A merge's move of the source's articles, main flag and all. Run demoteMainBySubjectId on the
    // source first when the target already has a main article, or uq_articles_main_per_subject fails.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Article a set a.subjectId = :toSubjectId where a.subjectId = :fromSubjectId")
    int moveToSubject(@Param("fromSubjectId") UUID fromSubjectId, @Param("toSubjectId") UUID toSubjectId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Article a set a.isMain = false where a.subjectId = :subjectId and a.isMain = true")
    int demoteMainBySubjectId(@Param("subjectId") UUID subjectId);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);

    // Used only by the dev demo-content seeder, to spread its articles over time. Both columns are
    // stamped in @PrePersist, so a bulk update is the only way to set them; it also covers the
    // article's translations, which the reader page shows as "last edited".
    @Modifying
    @Query(value = """
            with moved as (
                update articles set created_at = :createdAt, updated_at = :updatedAt where id = :articleId
                returning id
            )
            update article_translations set created_at = :createdAt, updated_at = :updatedAt
            where article_id in (select id from moved)
            """, nativeQuery = true)
    int backdate(@Param("articleId") UUID articleId, @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    long countByDevMarker(String devMarker);
}

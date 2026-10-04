package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleTranslationRepository extends JpaRepository<ArticleTranslation, UUID> {

    Optional<ArticleTranslation> findByArticleIdAndLanguage(UUID articleId, String language);

    Optional<ArticleTranslation> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<ArticleTranslation> findByArticleId(UUID articleId);

    boolean existsByArticleIdAndCurrentRevisionIdIsNotNull(UUID articleId);

    // Translations in one language that have an approved revision, filtered by their article's
    // subject and/or that subject's kind (either filter null = not applied). The left join keeps
    // subject-less articles when no kind is asked for; a kind filter drops them, since they have
    // no kind to match. Ordered newest article first, with id as tiebreak so paging is stable.
    @Query(value = """
            select t from ArticleTranslation t join Article a on a.id = t.articleId
              left join Subject s on s.id = a.subjectId
            where t.language = :language and t.currentRevisionId is not null
              and (:subjectId is null or a.subjectId = :subjectId)
              and (:subjectKind is null or s.kind = :subjectKind)
            order by a.createdAt desc, a.id""",
            countQuery = """
            select count(t) from ArticleTranslation t join Article a on a.id = t.articleId
              left join Subject s on s.id = a.subjectId
            where t.language = :language and t.currentRevisionId is not null
              and (:subjectId is null or a.subjectId = :subjectId)
              and (:subjectKind is null or s.kind = :subjectKind)""")
    Page<ArticleTranslation> findPublishedByLanguage(@Param("language") String language,
            @Param("subjectId") UUID subjectId, @Param("subjectKind") SubjectKind subjectKind, Pageable pageable);

    // The approved translations, in the given languages, of each subject's main article. Until
    // phase 6 stores one, a subject's main article is its oldest article with an approved
    // translation -- the article phase 6 will make main first. SubjectFacetRepository.MAIN_ARTICLE_OF_S
    // applies the same rule inside the facet queries.
    @Query(value = """
            select m.subject_id as subjectId, t.language as language, t.slug as slug
            from (select distinct on (a.subject_id) a.subject_id, a.id
                  from articles a
                  where a.subject_id in (:subjectIds)
                    and exists (select 1 from article_translations p
                                where p.article_id = a.id and p.current_revision_id is not null)
                  order by a.subject_id, a.created_at, a.id) m
            join article_translations t on t.article_id = m.id
            where t.current_revision_id is not null and t.language in (:languages)
            """, nativeQuery = true)
    List<MainArticleSlug> findMainArticleSlugs(@Param("subjectIds") Collection<UUID> subjectIds,
            @Param("languages") Collection<String> languages);

    interface MainArticleSlug {

        UUID getSubjectId();

        String getLanguage();

        String getSlug();
    }
}

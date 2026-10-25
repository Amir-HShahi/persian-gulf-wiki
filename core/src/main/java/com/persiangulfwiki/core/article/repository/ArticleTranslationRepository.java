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
    // subjects (any of subjectIds, if filterBySubject), that subject's kind and/or a live topic
    // (kind/topic null = not applied). The left join
    // keeps subject-less articles when no kind is asked for; a kind filter drops them, since they have
    // no kind to match. Articles about a deleted subject are left out, as from the per-topic counts
    // (TopicRepository.countPublishedArticlesByTopic), which also read topics from the same
    // Passed as subjectIds when filterBySubject is false: an empty collection in `in` fails on some
    // Hibernate versions, and the flag already short-circuits the clause.
    List<UUID> NO_SUBJECT_FILTER = List.of(new UUID(0, 0));

    // ArticleLiveTopic view -- so a topic's filtered total equals its count. Ordered newest article
    // first, with id as tiebreak so paging is stable.
    @Query(value = """
            select t from ArticleTranslation t join Article a on a.id = t.articleId
              left join Subject s on s.id = a.subjectId
            where t.language = :language and t.currentRevisionId is not null
              and (s.id is null or s.deletedAt is null)
              and (:filterBySubject = false or a.subjectId in :subjectIds)
              and (:subjectKind is null or s.kind = :subjectKind)
              and (:topicId is null or exists (select 1 from ArticleLiveTopic lt
                    where lt.articleId = a.id and lt.topicId = :topicId))
            order by a.createdAt desc, a.id""",
            countQuery = """
            select count(t) from ArticleTranslation t join Article a on a.id = t.articleId
              left join Subject s on s.id = a.subjectId
            where t.language = :language and t.currentRevisionId is not null
              and (s.id is null or s.deletedAt is null)
              and (:filterBySubject = false or a.subjectId in :subjectIds)
              and (:subjectKind is null or s.kind = :subjectKind)
              and (:topicId is null or exists (select 1 from ArticleLiveTopic lt
                    where lt.articleId = a.id and lt.topicId = :topicId))""")
    Page<ArticleTranslation> findPublishedByLanguage(@Param("language") String language,
            @Param("filterBySubject") boolean filterBySubject, @Param("subjectIds") Collection<UUID> subjectIds,
            @Param("subjectKind") SubjectKind subjectKind,
            @Param("topicId") UUID topicId, Pageable pageable);

    List<ArticleTranslation> findByArticleIdInAndCurrentRevisionIdIsNotNull(Collection<UUID> articleIds);

    // The approved translations, in the given languages, of each subject's main article.
    @Query("""
            select a.subjectId as subjectId, t.language as language, t.slug as slug
            from Article a join ArticleTranslation t on t.articleId = a.id
            where a.subjectId in :subjectIds and a.isMain = true
              and t.currentRevisionId is not null and t.language in :languages""")
    List<MainArticleSlug> findMainArticleSlugs(@Param("subjectIds") Collection<UUID> subjectIds,
            @Param("languages") Collection<String> languages);

    interface MainArticleSlug {

        UUID getSubjectId();

        String getLanguage();

        String getSlug();
    }
}

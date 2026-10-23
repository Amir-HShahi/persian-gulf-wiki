package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.FeaturedArticle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FeaturedArticleRepository extends JpaRepository<FeaturedArticle, UUID> {

    // The windows live at `now` whose article is published in `language`, with that translation,
    // latest-started window first. Same publication filter as
    // ArticleTranslationRepository.findPublishedByLanguage: an approved revision, no deleted subject.
    @Query("""
            select f.id as featuredArticleId, t.id as translationId
            from FeaturedArticle f join Article a on a.id = f.articleId
              join ArticleTranslation t on t.articleId = a.id
              left join Subject s on s.id = a.subjectId
            where f.startsAt <= :now and (f.endsAt is null or f.endsAt > :now)
              and t.language = :language and t.currentRevisionId is not null
              and (s.id is null or s.deletedAt is null)
            order by f.startsAt desc, f.createdAt desc, f.id""")
    List<LiveFeature> findLive(@Param("language") String language, @Param("now") Instant now, Pageable pageable);

    Page<FeaturedArticle> findAllByOrderByStartsAtDescIdAsc(Pageable pageable);

    @Modifying
    @Query("delete from FeaturedArticle f where f.devMarker = :devMarker and f.createdAt < :threshold")
    int deleteByDevMarkerAndCreatedAtBefore(@Param("devMarker") String devMarker,
            @Param("threshold") Instant threshold);

    interface LiveFeature {

        UUID getFeaturedArticleId();

        UUID getTranslationId();
    }
}

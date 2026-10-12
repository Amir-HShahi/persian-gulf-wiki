package com.persiangulfwiki.core.topic.repository;

import com.persiangulfwiki.core.topic.entity.Topic;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TopicRepository extends JpaRepository<Topic, UUID> {

    List<Topic> findAllByOrderBySortOrderAscSlugAsc();

    List<Topic> findAllByIsArchivedFalseOrderBySortOrderAscSlugAsc();

    List<Topic> findAllByIdIn(Collection<UUID> ids);

    Optional<Topic> findBySlugAndIsArchivedFalse(String slug);

    boolean existsBySlug(String slug);

    // Null when there are no topics.
    @Query("select max(t.sortOrder) from Topic t")
    @Nullable Integer findMaxSortOrder();

    // Per topic, the articles whose live topics include it and that have a published translation in
    // `language`. The live topics come from the article_live_topics view (V32), which the article
    // list's topic filter reads too -- keep both on it so a topic's count and its filtered list
    // total stay equal. Articles about a deleted subject are left out, as from that list. Topics
    // with no such article are absent.
    @Query(value = """
            select lt.topic_id, count(distinct a.id)
            from articles a
            join article_live_topics lt on lt.article_id = a.id
            join article_translations reader
              on reader.article_id = a.id and reader.language = :language
             and reader.current_revision_id is not null
            left join subjects s on s.id = a.subject_id
            where s.id is null or s.deleted_at is null
            group by lt.topic_id""", nativeQuery = true)
    List<Object[]> countPublishedArticlesByTopic(@Param("language") String language);

    @Query(value = "select exists (select 1 from revision_topics where topic_id = :topicId)", nativeQuery = true)
    boolean isInUse(@Param("topicId") UUID topicId);

    @Modifying
    @Query(value = """
            delete from topics t
            where t.dev_marker = :devMarker and t.created_at < :threshold
              and not exists (select 1 from revision_topics rt where rt.topic_id = t.id)""", nativeQuery = true)
    int deleteUnusedByDevMarkerAndCreatedAtBefore(@Param("devMarker") String devMarker,
            @Param("threshold") Instant threshold);
}

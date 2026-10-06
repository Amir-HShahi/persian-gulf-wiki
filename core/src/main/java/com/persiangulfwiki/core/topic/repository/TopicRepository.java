package com.persiangulfwiki.core.topic.repository;

import com.persiangulfwiki.core.topic.entity.Topic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TopicRepository extends JpaRepository<Topic, UUID> {

    List<Topic> findAllByOrderBySortOrderAscSlugAsc();

    long countByIdIn(Collection<UUID> ids);

    // Per topic, the articles whose live topics include it and that have a published translation in
    // `language`. The live topics are those of the canonical translation's current revision -- an
    // article's own topics, which its other translations inherit. Articles about a deleted subject
    // are left out, as from every public read. Topics with no such article are absent.
    @Query(value = """
            select rt.topic_id, count(distinct a.id)
            from articles a
            join article_translations canonical
              on canonical.article_id = a.id and canonical.language = a.canonical_language
            join revision_topics rt on rt.revision_id = canonical.current_revision_id
            join article_translations reader
              on reader.article_id = a.id and reader.language = :language
             and reader.current_revision_id is not null
            left join subjects s on s.id = a.subject_id
            where s.id is null or s.deleted_at is null
            group by rt.topic_id""", nativeQuery = true)
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

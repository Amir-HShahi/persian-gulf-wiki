package com.persiangulfwiki.core.moderation.repository;

import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

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

public interface ModerationTaskRepository extends JpaRepository<ModerationTask, UUID> {

    // At most one task can exist per revision (uq_moderation_tasks_revision, V17), so this
    // is a total lookup rather than a "pick the live one out of several" query.
    Optional<ModerationTask> findByRevisionId(UUID revisionId);

    // Same totality for metadata versions (uq_moderation_tasks_media_metadata_version, V18).
    boolean existsByMediaMetadataVersionId(UUID mediaMetadataVersionId);

    Optional<ModerationTask> findByMediaMetadataVersionId(UUID mediaMetadataVersionId);

    // Same totality for subject revisions (uq_moderation_tasks_subject_revision, V24).
    Optional<ModerationTask> findBySubjectRevisionId(UUID subjectRevisionId);

    Page<ModerationTask> findByState(ModerationTaskState state, Pageable pageable);

    long deleteByDevMarkerAndUpdatedAtBefore(String devMarker, Instant threshold);

    // Dashboard queries. Article tasks only (revision_id set); a task's created_at is when its
    // revision was first submitted -- a resubmit after REQUEST_CHANGES reopens the same task.
    @Query(value = """
            select count(*) from moderation_tasks t
            join article_revisions r on r.id = t.revision_id
            where t.created_at >= :from and t.created_at < :to
              and r.status in ('PENDING', 'CHANGES_REQUESTED')
            """, nativeQuery = true)
    long countStillPendingArticlesSubmittedBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select r.status as status, count(*) as count from moderation_tasks t
            join article_revisions r on r.id = t.revision_id
            where t.created_at >= :from and t.created_at < :to
            group by r.status
            """, nativeQuery = true)
    List<StatusCount> countArticlesByRevisionStatusSubmittedBetween(@Param("from") Instant from,
            @Param("to") Instant to);

    // Dev fixtures only: @PrePersist always stamps created_at with now, so backdating a minted
    // task is a second statement. The marker guard keeps it off any real row.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update moderation_tasks set created_at = :at where id = :id and dev_marker = :marker",
            nativeQuery = true)
    int backdateDevTask(@Param("id") UUID id, @Param("marker") String marker, @Param("at") Instant at);
}

package com.persiangulfwiki.core.moderation.repository;

import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ModerationTaskRepository extends JpaRepository<ModerationTask, UUID> {

    // At most one task can exist per revision (uq_moderation_tasks_revision, V17), so this
    // is a total lookup rather than a "pick the live one out of several" query.
    Optional<ModerationTask> findByRevisionId(UUID revisionId);

    Page<ModerationTask> findByState(ModerationTaskState state, Pageable pageable);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

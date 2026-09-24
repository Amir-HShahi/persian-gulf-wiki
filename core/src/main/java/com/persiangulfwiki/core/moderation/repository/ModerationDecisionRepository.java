package com.persiangulfwiki.core.moderation.repository;

import com.persiangulfwiki.core.moderation.entity.ModerationDecision;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModerationDecisionRepository extends JpaRepository<ModerationDecision, UUID> {

    List<ModerationDecision> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}

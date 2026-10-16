package com.persiangulfwiki.core.moderation.repository;

import com.persiangulfwiki.core.moderation.entity.ModerationDecision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ModerationDecisionRepository extends JpaRepository<ModerationDecision, UUID> {

    List<ModerationDecision> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    // decision is a Decision name; article tasks only (revision_id set).
    @Query(value = """
            select count(*) from moderation_decisions d
            join moderation_tasks t on t.id = d.task_id
            where t.revision_id is not null and d.decision = :decision
              and d.created_at >= :from and d.created_at < :to
            """, nativeQuery = true)
    long countArticleDecisionsBetween(@Param("decision") String decision, @Param("from") Instant from,
            @Param("to") Instant to);

    // zone is an IANA id; days with no approval are absent, not zero.
    @Query(value = """
            select to_char((d.created_at at time zone :zone)::date, 'YYYY-MM-DD') as day, count(*) as count
            from moderation_decisions d
            join moderation_tasks t on t.id = d.task_id
            where t.revision_id is not null and d.decision = 'APPROVE'
              and d.created_at >= :from and d.created_at < :to
            group by 1
            """, nativeQuery = true)
    List<DayCount> countArticleApprovalsPerDayBetween(@Param("zone") String zone, @Param("from") Instant from,
            @Param("to") Instant to);
}

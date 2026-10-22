package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectRedirect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

// Keyed by the merged-away subject's id.
public interface SubjectRedirectRepository extends JpaRepository<SubjectRedirect, UUID> {

    // Keeps redirects one hop deep when `fromTargetId` is itself merged away.
    @Modifying(flushAutomatically = true)
    @Query("update SubjectRedirect r set r.targetId = :toTargetId where r.targetId = :fromTargetId")
    int repointTargets(@Param("fromTargetId") UUID fromTargetId, @Param("toTargetId") UUID toTargetId);
}

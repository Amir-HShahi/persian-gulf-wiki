package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectRevision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SubjectRevisionRepository extends JpaRepository<SubjectRevision, UUID> {

    // 0 for a subject with no revision yet. Only meaningful under the subject row lock
    // SubjectRevisionService takes before allocating, which serializes concurrent allocations.
    @Query("select coalesce(max(r.revisionNumber), 0) from SubjectRevision r where r.subjectId = :subjectId")
    int findMaxRevisionNumber(@Param("subjectId") UUID subjectId);

    List<SubjectRevision> findBySubjectIdOrderByRevisionNumberAsc(UUID subjectId);

    // Skips a live revision: subject_live_revisions points at it, and it goes with its subject
    // through that subject's own sweep instead. Bulk on purpose -- the moderation task and any
    // revision based on this one are handled by V24's CASCADE and SET NULL.
    @Modifying
    @Query("""
            delete from SubjectRevision r
            where r.devMarker = :devMarker and r.createdAt < :threshold
              and not exists (select 1 from SubjectLiveRevision l where l.revisionId = r.id)
            """)
    int deleteUnpublishedByDevMarkerAndCreatedAtBefore(@Param("devMarker") String devMarker,
            @Param("threshold") Instant threshold);
}

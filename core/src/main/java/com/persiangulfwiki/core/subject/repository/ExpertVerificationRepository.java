package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.ExpertVerification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExpertVerificationRepository extends JpaRepository<ExpertVerification, UUID> {

    boolean existsBySubjectRevisionIdAndExpertId(UUID subjectRevisionId, UUID expertId);

    // The newest revision of each subject that carries at least one mark; a subject with no mark
    // on any revision has no row.
    @Query("""
            select r.subjectId as subjectId, max(r.revisionNumber) as revisionNumber
            from ExpertVerification v join SubjectRevision r on r.id = v.subjectRevisionId
            where r.subjectId in :subjectIds
            group by r.subjectId
            """)
    List<LatestVerifiedRevision> findLatestVerifiedRevisions(@Param("subjectIds") Collection<UUID> subjectIds);

    interface LatestVerifiedRevision {

        UUID getSubjectId();

        int getRevisionNumber();
    }
}

package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

// Keyed by subject id: at most one live revision per subject.
public interface SubjectLiveRevisionRepository extends JpaRepository<SubjectLiveRevision, UUID> {

    boolean existsByRevisionId(UUID revisionId);

    // The expert verification queue: live revisions of the given kinds that carry no mark yet
    // (NONE or STALE), leaving out the expert's own, since nobody may verify what they wrote.
    // Newest approval first.
    @Query("""
            select l from SubjectLiveRevision l, SubjectRevision r
            where r.id = l.revisionId and r.kind in :kinds and r.authorId <> :expertId
              and not exists (select 1 from ExpertVerification v where v.subjectRevisionId = l.revisionId)
            order by l.approvedAt desc, l.subjectId
            """)
    List<SubjectLiveRevision> findUnverified(@Param("kinds") Collection<SubjectKind> kinds,
            @Param("expertId") UUID expertId, Pageable pageable);
}

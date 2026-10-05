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
    // (NONE or STALE), leaving out the expert's own, since nobody may verify what they wrote, and
    // those of deleted subjects, which can't be verified. Newest approval first.
    @Query("""
            select l from SubjectLiveRevision l, SubjectRevision r, Subject s
            where r.id = l.revisionId and r.kind in :kinds and r.authorId <> :expertId
              and s.id = l.subjectId and s.deletedAt is null
              and not exists (select 1 from ExpertVerification v where v.subjectRevisionId = l.revisionId)
            order by l.approvedAt desc, l.subjectId
            """)
    List<SubjectLiveRevision> findUnverified(@Param("kinds") Collection<SubjectKind> kinds,
            @Param("expertId") UUID expertId, Pageable pageable);

    // The main image each live revision picked, read straight out of its payload: the payload is
    // the only place it is stored (see SubjectReadModelService). Subjects that picked none are absent.
    @Query(value = """
            select l.subject_id as subjectId, cast(r.payload ->> 'mainImageMediaId' as uuid) as mediaId
            from subject_live_revisions l join subject_revisions r on r.id = l.revision_id
            where l.subject_id in (:subjectIds) and r.payload ->> 'mainImageMediaId' is not null
            """, nativeQuery = true)
    List<MainImage> findMainImages(@Param("subjectIds") Collection<UUID> subjectIds);

    interface MainImage {

        UUID getSubjectId();

        UUID getMediaId();
    }
}

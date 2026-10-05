package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.persistence.LockModeType;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID>, SubjectFacetRepository {

    List<Subject> findByKind(SubjectKind kind);

    @Query(value = "select " + HAS_360_TOUR_OF_S + " from subjects s where s.id = :subjectId", nativeQuery = true)
    boolean hasPublished360Tour(@Param("subjectId") UUID subjectId);

    // Serializes everything that reads and then moves a subject's revision state: allocating the
    // next revision number, and approval's stale-base check followed by the read model rewrite.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subject s where s.id = :id")
    Optional<Subject> findByIdForUpdate(@Param("id") UUID id);

    // Subjects with a PREFERRED value of the SUBJECT_REF field pointing at subjectId, optionally of one
    // kind (null = any). Newest first, then by id, like the listing.
    @Query(value = """
            select s.id from Subject s
            where exists (select 1 from SubjectFact f
                          where f.subjectId = s.id and f.attributeId = :attributeId
                            and f.valueRefSubjectId = :subjectId
                            and f.rank = com.persiangulfwiki.core.subject.entity.FactRank.PREFERRED)
              and (:kind is null or s.kind = :kind)
            order by s.createdAt desc, s.id""",
            countQuery = """
            select count(s) from Subject s
            where exists (select 1 from SubjectFact f
                          where f.subjectId = s.id and f.attributeId = :attributeId
                            and f.valueRefSubjectId = :subjectId
                            and f.rank = com.persiangulfwiki.core.subject.entity.FactRank.PREFERRED)
              and (:kind is null or s.kind = :kind)""")
    Page<UUID> findIdsReferencing(@Param("subjectId") UUID subjectId, @Param("attributeId") UUID attributeId,
            @Param("kind") @Nullable SubjectKind kind, Pageable pageable);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

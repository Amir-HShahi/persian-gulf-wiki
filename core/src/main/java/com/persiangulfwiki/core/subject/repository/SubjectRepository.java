package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.persistence.LockModeType;

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

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

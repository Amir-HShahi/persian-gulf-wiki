package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    Page<Subject> findByKind(SubjectKind kind, Pageable pageable);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

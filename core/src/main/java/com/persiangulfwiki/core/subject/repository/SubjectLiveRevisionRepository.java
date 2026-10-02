package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Keyed by subject id: at most one live revision per subject.
public interface SubjectLiveRevisionRepository extends JpaRepository<SubjectLiveRevision, UUID> {

    boolean existsByRevisionId(UUID revisionId);
}

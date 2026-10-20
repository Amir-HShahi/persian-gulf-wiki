package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectAlias;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SubjectAliasRepository extends JpaRepository<SubjectAlias, UUID> {

    List<SubjectAlias> findBySubjectId(UUID subjectId);

    // Bulk for the same reason as SubjectFactRepository.deleteBySubjectId, and here it is
    // load-bearing: a re-approved alias would hit uq_subject_aliases_subject_language_value if
    // its insert ran before the old row's delete.
    @Modifying(flushAutomatically = true)
    @Query("delete from SubjectAlias a where a.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}

package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectFact;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface SubjectFactRepository extends JpaRepository<SubjectFact, UUID> {

    // Bulk, so it runs before the replacement rows are inserted rather than after them, which is
    // where Hibernate's flush order would put a derived delete.
    @Modifying(flushAutomatically = true)
    @Query("delete from SubjectFact f where f.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);

    boolean existsByAttributeId(UUID attributeId);

    @Query("""
            select count(f) > 0 from SubjectFact f, Subject s
            where f.attributeId = :attributeId and s.id = f.subjectId and s.kind in :kinds
            """)
    boolean existsByAttributeIdAndSubjectKindIn(@Param("attributeId") UUID attributeId,
            @Param("kinds") Collection<SubjectKind> kinds);

    @Query(value = """
            select exists (
                select 1 from subject_facts
                where attribute_id = :attributeId and rank = 'PREFERRED'
                group by subject_id
                having count(*) > 1)
            """, nativeQuery = true)
    boolean existsSubjectWithSeveralPreferredValues(@Param("attributeId") UUID attributeId);
}

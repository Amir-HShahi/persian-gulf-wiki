package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.FactRank;
import com.persiangulfwiki.core.subject.entity.SubjectFact;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SubjectFactRepository extends JpaRepository<SubjectFact, UUID> {

    // Bulk, so it runs before the replacement rows are inserted rather than after them, which is
    // where Hibernate's flush order would put a derived delete.
    @Modifying(flushAutomatically = true)
    @Query("delete from SubjectFact f where f.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);

    boolean existsByAttributeId(UUID attributeId);

    List<SubjectFact> findBySubjectIdAndRankOrderBySortAsc(UUID subjectId, FactRank rank);

    // The listing cards' badge codes: each PREFERRED value of a badge field, in field order.
    @Query("""
            select f.subjectId as subjectId, d.key as attribute, o.code as code
            from SubjectFact f, AttributeDefinition d, AttributeOption o
            where f.subjectId in :subjectIds and d.id = f.attributeId and o.id = f.valueOptionId
              and d.isBadge = true and f.rank = com.persiangulfwiki.core.subject.entity.FactRank.PREFERRED
            order by d.sort, d.key
            """)
    List<Badge> findBadges(@Param("subjectIds") Collection<UUID> subjectIds);

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

    interface Badge {

        UUID getSubjectId();

        String getAttribute();

        String getCode();
    }
}

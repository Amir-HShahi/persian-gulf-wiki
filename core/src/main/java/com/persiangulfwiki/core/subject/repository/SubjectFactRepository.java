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

    // Any rank, any subject (deleted ones too): value_ref_subject_id has no ON DELETE action, so each
    // of these would block a hard delete anyway.
    boolean existsByValueRefSubjectId(UUID subjectId);

    // A merge's repointing of other subjects' references (`excludedSubjectId` is the target, whose own
    // facts the merge has just rewritten from its payload). Can leave a subject holding the same
    // reference twice; removeDuplicateReferences cleans that up.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update subject_facts set value_ref_subject_id = :toSubjectId
            where value_ref_subject_id = :fromSubjectId and subject_id <> :excludedSubjectId
            """, nativeQuery = true)
    int repointReferences(@Param("fromSubjectId") UUID fromSubjectId, @Param("toSubjectId") UUID toSubjectId,
            @Param("excludedSubjectId") UUID excludedSubjectId);

    // Where one field of one subject refers to `subjectId` more than once, keeps the best of those
    // values -- PREFERRED over NORMAL, then the earliest -- and deletes the rest. A single-valued field
    // never ends up with two PREFERRED values: before the repointing it had at most one.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            delete from subject_facts f
            using subject_facts g
            where f.value_ref_subject_id = :subjectId and g.value_ref_subject_id = :subjectId
              and g.subject_id = f.subject_id and g.attribute_id = f.attribute_id and g.id <> f.id
              and ((g.rank = 'PREFERRED' and f.rank = 'NORMAL')
                   or (g.rank = f.rank and (g.sort < f.sort or (g.sort = f.sort and g.id < f.id))))
            """, nativeQuery = true)
    int removeDuplicateReferences(@Param("subjectId") UUID subjectId);

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

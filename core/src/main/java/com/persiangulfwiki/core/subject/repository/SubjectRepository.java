package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.persistence.LockModeType;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID>, SubjectFacetRepository {

    // Public reads see only subjects that are not deleted (soft-deleted or merged away).
    Optional<Subject> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<Subject> findByKindAndDeletedAtIsNull(SubjectKind kind);

    @Query("select s.id from Subject s where s.id in :ids and s.deletedAt is not null")
    Set<UUID> findDeletedIds(@Param("ids") Collection<UUID> ids);

    @Query(value = "select " + HAS_360_TOUR_OF_S + " from subjects s where s.id = :subjectId", nativeQuery = true)
    boolean hasPublished360Tour(@Param("subjectId") UUID subjectId);

    // Serializes everything that reads and then moves a subject's revision state: allocating the
    // next revision number, and approval's stale-base check followed by the read model rewrite.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subject s where s.id = :id")
    Optional<Subject> findByIdForUpdate(@Param("id") UUID id);

    // For binding a new article to a subject: waits out a soft delete, merge or hard delete holding
    // the row lock above, then sees its outcome, so no article lands on a subject that just stopped
    // being visible. Shared with other binders, so creates don't serialize against each other.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Subject s where s.id = :id")
    Optional<Subject> findByIdForShare(@Param("id") UUID id);

    // Subjects with a PREFERRED value of the SUBJECT_REF field pointing at subjectId, optionally of one
    // kind (null = any), leaving out deleted ones. Newest first, then by id, like the listing.
    @Query(value = """
            select s.id from Subject s
            where exists (select 1 from SubjectFact f
                          where f.subjectId = s.id and f.attributeId = :attributeId
                            and f.valueRefSubjectId = :subjectId
                            and f.rank = com.persiangulfwiki.core.subject.entity.FactRank.PREFERRED)
              and s.deletedAt is null
              and (:kind is null or s.kind = :kind)
            order by s.createdAt desc, s.id""",
            countQuery = """
            select count(s) from Subject s
            where exists (select 1 from SubjectFact f
                          where f.subjectId = s.id and f.attributeId = :attributeId
                            and f.valueRefSubjectId = :subjectId
                            and f.rank = com.persiangulfwiki.core.subject.entity.FactRank.PREFERRED)
              and s.deletedAt is null
              and (:kind is null or s.kind = :kind)""")
    Page<UUID> findIdsReferencing(@Param("subjectId") UUID subjectId, @Param("attributeId") UUID attributeId,
            @Param("kind") @Nullable SubjectKind kind, Pageable pageable);

    // The duplicate lookup: subjects of `kinds` whose name (any language), alias or scientific name
    // contains `query` once both are folded by subject_search_key (V29). One row per subject, its best
    // match: an exact match before a prefix before a substring, a name before an alias before a
    // scientific name, the reader's language first. Results go exact, prefix, substring, then newest
    // first. Deleted subjects never match. A query that folds to nothing matches nothing.
    @Query(value = """
            with q as (select subject_search_key(:query) as search_key),
            candidates as (
                select l.subject_id, l.value, l.language, 'NAME' as matched_on, 0 as source_rank,
                       subject_search_key(l.value) as search_key
                from labels l
                where l.subject_id is not null
                union all
                select a.subject_id, a.value, a.language, 'ALIAS', 1, subject_search_key(a.value)
                from subject_aliases a
                union all
                select f.subject_id, f.value_text, null, 'SCIENTIFIC_NAME', 2, subject_search_key(f.value_text)
                from subject_facts f join attribute_definitions d on d.id = f.attribute_id
                where d.key = :scientificNameKey and f.value_text is not null
            ),
            best as (
                select distinct on (c.subject_id)
                       c.subject_id, s.kind, c.value, c.language, c.matched_on, s.created_at,
                       case when c.search_key = q.search_key then 0
                            when starts_with(c.search_key, q.search_key) then 1
                            else 2 end as match_rank
                from candidates c cross join q join subjects s on s.id = c.subject_id
                where q.search_key <> '' and strpos(c.search_key, q.search_key) > 0
                  and s.deleted_at is null and s.kind in (:kinds)
                order by c.subject_id, match_rank, c.source_rank, (c.language = :language) desc nulls last, c.value
            )
            select subject_id as subjectId, kind, value as matchedValue, language as matchedLanguage,
                   matched_on as matchedOn
            from best
            order by match_rank, created_at desc, subject_id
            limit :size
            """, nativeQuery = true)
    List<LookupMatch> lookup(@Param("query") String query, @Param("kinds") Collection<String> kinds,
            @Param("scientificNameKey") String scientificNameKey, @Param("language") String language,
            @Param("size") int size);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);

    // The marked subjects the dev demo-content wipe must keep: those an unmarked article or fact still
    // points at, and everything they reference in turn (the recursive part), because deleting any of
    // them would fail on articles.subject_id or subject_facts.value_ref_subject_id (both NO ACTION).
    String KEPT_MARKED_SUBJECTS = """
            with recursive kept(id) as (
                select s.id from subjects s
                where s.dev_marker = :devMarker
                  and (exists (select 1 from articles a
                               where a.subject_id = s.id and a.dev_marker is distinct from :devMarker)
                       or exists (select 1 from subject_facts f join subjects o on o.id = f.subject_id
                                  where f.value_ref_subject_id = s.id
                                    and o.dev_marker is distinct from :devMarker))
                union
                select f.value_ref_subject_id from subject_facts f join kept k on k.id = f.subject_id
                where f.value_ref_subject_id is not null
            )
            """;

    // Used only by the dev demo-content wipe, and run before deleteByDevMarkerExceptKept. Marked
    // subjects reference each other through their facts, and Postgres checks a NO ACTION reference
    // before the facts that go with a subject through ON DELETE CASCADE are gone -- even inside one
    // DELETE -- so the facts of every subject about to be deleted are removed in a statement of their own.
    @Modifying
    @Query(value = KEPT_MARKED_SUBJECTS + """
            delete from subject_facts f
            using subjects s
            where s.id = f.subject_id and s.dev_marker = :devMarker and s.id not in (select id from kept)
            """, nativeQuery = true)
    int deleteFactsOfMarkedSubjectsExceptKept(@Param("devMarker") String devMarker);

    @Modifying
    @Query(value = KEPT_MARKED_SUBJECTS + """
            delete from subjects s
            where s.dev_marker = :devMarker and s.id not in (select id from kept)
            """, nativeQuery = true)
    int deleteByDevMarkerExceptKept(@Param("devMarker") String devMarker);

    long countByDevMarker(String devMarker);

    interface LookupMatch {

        UUID getSubjectId();

        String getKind();

        String getMatchedValue();

        // Null for a scientific name, which has no language.
        @Nullable
        String getMatchedLanguage();

        // NAME, ALIAS or SCIENTIFIC_NAME.
        String getMatchedOn();
    }
}

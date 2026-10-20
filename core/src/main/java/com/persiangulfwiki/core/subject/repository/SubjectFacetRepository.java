package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// The facet listing's queries, mixed into SubjectRepository. A fragment rather than @Query methods
// because the number of conditions depends on the request: each selected field adds one EXISTS.
// Everything reads the read model only (subject_facts, PREFERRED values), so pending and rejected
// revisions never show here, and deleted subjects are left out of every query.
public interface SubjectFacetRepository {

    // A subject's main article (V28). Written against a `subjects s` row.
    String MAIN_ARTICLE_OF_S = "(select a.id from articles a where a.subject_id = s.id and a.is_main)";

    // The derived has_360_tour, computed rather than stored: a public 360° panorama in the main
    // article's gallery.
    // Concatenated rather than formatted so it stays a constant, usable in a @Query.
    String HAS_360_TOUR_OF_S = "exists (select 1 from article_media m where m.article_id = " + MAIN_ARTICLE_OF_S
            + " and m.type = 'PANORAMA_360' and m.processing_status = 'READY'"
            + " and m.publication_status = 'PUBLISHED')";

    // Newest first, then by id, so a page boundary is stable.
    List<UUID> findMatchingIds(SubjectFacetFilter filter, long offset, int limit);

    long countMatching(SubjectFacetFilter filter);

    // Distinct matching subjects per option of the field; options no subject holds are absent.
    Map<UUID, Long> countByOption(UUID attributeId, SubjectFacetFilter filter);

    // Distinct matching subjects per subject the field refers to; unreferenced ones are absent.
    Map<UUID, Long> countByReferencedSubject(UUID attributeId, SubjectFacetFilter filter);

    // Kinds with no matching subject are absent.
    Map<SubjectKind, Long> countByKind(SubjectFacetFilter filter);
}

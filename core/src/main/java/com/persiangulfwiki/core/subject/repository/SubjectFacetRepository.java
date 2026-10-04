package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// The facet listing's queries, mixed into SubjectRepository. A fragment rather than @Query methods
// because the number of conditions depends on the request: each selected field adds one EXISTS.
// Everything reads the read model only (subject_facts, PREFERRED values), so pending and rejected
// revisions never show here.
public interface SubjectFacetRepository {

    // A subject's main article until phase 6 stores one: its oldest article with an approved
    // translation, which is the article phase 6 makes main first. Written against a `subjects s`
    // row. ArticleTranslationRepository.findMainArticleSlugs applies the same rule.
    String MAIN_ARTICLE_OF_S = """
            (select a.id from articles a
             where a.subject_id = s.id
               and exists (select 1 from article_translations t
                           where t.article_id = a.id and t.current_revision_id is not null)
             order by a.created_at, a.id
             limit 1)""";

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

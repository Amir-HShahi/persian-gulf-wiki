package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.ArticleRevision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRevisionRepository extends JpaRepository<ArticleRevision, UUID> {

    List<ArticleRevision> findByTranslationIdOrderByRevisionNumberAsc(UUID translationId);

    Optional<ArticleRevision> findByTranslationIdAndRevisionNumber(UUID translationId, int revisionNumber);

    // Used to allocate the next revisionNumber for a translation (max + 1, or 1 if none
    // exist yet). A derived-query equivalent would need a nullable Integer return type to
    // represent "no revisions yet", which is more awkward at the call site than defaulting
    // in the query itself.
    @Query("select coalesce(max(r.revisionNumber), 0) from ArticleRevision r where r.translationId = :translationId")
    int findMaxRevisionNumber(@Param("translationId") UUID translationId);
}

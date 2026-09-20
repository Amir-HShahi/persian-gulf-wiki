package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.EntityType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    Page<Article> findBySubjectId(UUID subjectId, Pageable pageable);

    Page<Article> findByEntityType(EntityType entityType, Pageable pageable);

    Page<Article> findBySubjectIdAndEntityType(UUID subjectId, EntityType entityType, Pageable pageable);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

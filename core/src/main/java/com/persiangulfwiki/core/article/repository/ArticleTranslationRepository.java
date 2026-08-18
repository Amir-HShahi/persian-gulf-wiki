package com.persiangulfwiki.core.article.repository;

import com.persiangulfwiki.core.article.entity.ArticleTranslation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleTranslationRepository extends JpaRepository<ArticleTranslation, UUID> {

    Optional<ArticleTranslation> findByArticleIdAndLanguage(UUID articleId, String language);

    Optional<ArticleTranslation> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<ArticleTranslation> findByArticleId(UUID articleId);
}

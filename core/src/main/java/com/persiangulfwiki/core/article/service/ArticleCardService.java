package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.service.SubjectMainImageService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Builds ArticleCardResponse, the approved-text summary that ArticleResponse and SubjectArticleResponse
// show flat. Four queries for any number of articles: translations, revisions, and the subjects'
// main images (two).
@Service
@RequiredArgsConstructor
public class ArticleCardService {

    private static final String FARSI = SupportedLocales.FARSI.getLanguage();

    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final SubjectMainImageService subjectMainImageService;

    @Transactional(readOnly = true)
    public Map<UUID, ArticleCardResponse> cardsOf(Collection<Article> articles, String language) {
        if (articles.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ArticleTranslation>> translationsByArticle = articleTranslationRepository
                .findByArticleIdInAndCurrentRevisionIdIsNotNull(articles.stream().map(Article::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ArticleTranslation::getArticleId));
        Map<UUID, ArticleTranslation> shown = new HashMap<>();
        for (Article article : articles) {
            List<ArticleTranslation> translations = translationsByArticle.getOrDefault(article.getId(), List.of());
            if (!translations.isEmpty()) {
                shown.put(article.getId(), pick(translations, article.getCanonicalLanguage(), language));
            }
        }
        Map<UUID, ArticleRevision> revisions = articleRevisionRepository
                .findAllById(shown.values().stream().map(ArticleTranslation::getCurrentRevisionId).toList())
                .stream()
                .collect(Collectors.toMap(ArticleRevision::getId, Function.identity()));
        Map<UUID, SubjectMainImageResponse> images = subjectMainImageService.mainImages(
                articles.stream().map(Article::getSubjectId).filter(Objects::nonNull).distinct().toList());

        Map<UUID, ArticleCardResponse> cards = new HashMap<>();
        for (Article article : articles) {
            ArticleTranslation translation = shown.get(article.getId());
            ArticleRevision revision = translation == null ? null : revisions.get(translation.getCurrentRevisionId());
            SubjectMainImageResponse image = article.getSubjectId() == null ? null : images.get(article.getSubjectId());
            cards.put(article.getId(), translation == null || revision == null
                    ? new ArticleCardResponse(article.getId(), null, null, null, null, null, image)
                    : new ArticleCardResponse(article.getId(), translation.getLanguage(), translation.getSlug(),
                            revision.getTitle(), revision.getSummary(), revision.getId(), image));
        }
        return Map.copyOf(cards);
    }

    // The reader's language, else Farsi, else the article's own language, else the first by code.
    static ArticleTranslation pick(List<ArticleTranslation> translations, String canonicalLanguage, String language) {
        // false sorts before true, so each "is not X" key puts X first.
        Comparator<ArticleTranslation> preference = Comparator
                .comparing((ArticleTranslation translation) -> !translation.getLanguage().equals(language))
                .thenComparing(translation -> !translation.getLanguage().equals(FARSI))
                .thenComparing(translation -> !translation.getLanguage().equals(canonicalLanguage))
                .thenComparing(ArticleTranslation::getLanguage);
        return translations.stream().min(preference).orElseThrow();
    }
}

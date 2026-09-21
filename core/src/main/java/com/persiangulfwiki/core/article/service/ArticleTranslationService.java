package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.dto.TranslationResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.DuplicateSlugException;
import com.persiangulfwiki.core.article.exception.DuplicateTranslationLanguageException;
import com.persiangulfwiki.core.article.exception.TranslationNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticleTranslationService {

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionService articleRevisionService;

    // Adds a language to an existing article, writing that language's first DRAFT revision in
    // the same transaction -- same "detail row must exist together with its parent" reasoning
    // as ArticleService.create.
    @Transactional
    public TranslationResponse addTranslation(UUID authorUserId, UUID articleId, CreateTranslationRequest request) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);

        if (articleTranslationRepository.findByArticleIdAndLanguage(articleId, request.language()).isPresent()) {
            throw new DuplicateTranslationLanguageException();
        }
        if (articleTranslationRepository.existsBySlug(request.slug())) {
            throw new DuplicateSlugException();
        }

        // Starts UP_TO_DATE, pointed at whatever the canonical translation's current
        // revision is right now (null if the canonical translation somehow has none yet).
        // Phase 4's sync tooling is what would ever move this to OUTDATED once the canonical
        // translation advances past sourceRevisionId.
        UUID sourceRevisionId = articleTranslationRepository
                .findByArticleIdAndLanguage(articleId, article.getCanonicalLanguage())
                .map(ArticleTranslation::getCurrentRevisionId)
                .orElse(null);

        ArticleTranslation translation = articleTranslationRepository.save(ArticleTranslation.builder()
                .articleId(articleId)
                .language(request.language())
                .slug(request.slug())
                .sourceRevisionId(sourceRevisionId)
                .translationState(TranslationState.UP_TO_DATE)
                .build());

        // Left null on purpose -- see the same decision in ArticleService.create. A new
        // language starts unpublished like any other draft, and stays that way until a
        // moderator approves its first revision.
        articleRevisionService.createInitialRevision(
                translation.getId(), request.title(), request.body(), request.summary(), authorUserId);

        return toResponse(translation);
    }

    @Transactional(readOnly = true)
    public TranslationResponse get(UUID articleId, String language) {
        return toResponse(articleTranslationRepository.findByArticleIdAndLanguage(articleId, language)
                .orElseThrow(TranslationNotFoundException::new));
    }

    private TranslationResponse toResponse(ArticleTranslation translation) {
        return new TranslationResponse(translation.getId(), translation.getArticleId(), translation.getLanguage(),
                translation.getSlug(), translation.getCurrentRevisionId(), translation.getSourceRevisionId(),
                translation.getTranslationState(), translation.getCreatedAt(), translation.getUpdatedAt());
    }
}

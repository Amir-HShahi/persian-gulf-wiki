package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleResponse;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.DuplicateSlugException;
import com.persiangulfwiki.core.article.exception.EntityTypeNotDerivableException;
import com.persiangulfwiki.core.article.exception.InvalidEntityTypeException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionService articleRevisionService;
    private final SubjectRepository subjectRepository;

    // Article + its canonical translation + that translation's first DRAFT revision, written
    // together or not at all: an article with zero translations can never be reached by
    // GET .../translations/{language}, and nothing in this API creates a translation on its
    // own for a brand-new article -- same "detail row must exist or the parent is
    // unaddressable" reasoning as SubjectService.create.
    @Transactional
    public ArticleResponse create(UUID creatorUserId, CreateArticleRequest request) {
        EntityType entityType = resolveEntityType(request.subjectId(), request.entityType());

        if (articleTranslationRepository.existsBySlug(request.slug())) {
            throw new DuplicateSlugException();
        }

        Article article = articleRepository.save(Article.builder()
                .subjectId(request.subjectId())
                .entityType(entityType)
                .canonicalLanguage(request.canonicalLanguage())
                .createdByUserId(creatorUserId)
                .build());

        ArticleTranslation translation = articleTranslationRepository.save(ArticleTranslation.builder()
                .articleId(article.getId())
                .language(request.canonicalLanguage())
                .slug(request.slug())
                // The canonical translation is trivially in sync with itself -- see
                // TranslationState.UP_TO_DATE.
                .translationState(TranslationState.UP_TO_DATE)
                .build());

        UUID revisionId = articleRevisionService.createInitialRevision(
                translation.getId(), request.title(), request.body(), request.summary(), creatorUserId);

        translation.setCurrentRevisionId(revisionId);
        articleTranslationRepository.save(translation);

        return toResponse(article);
    }

    @Transactional(readOnly = true)
    public ArticleResponse get(UUID articleId) {
        return toResponse(articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<ArticleResponse> list(UUID subjectId, String entityTypeFilter, Pageable pageable) {
        EntityType entityType = entityTypeFilter == null ? null : parseEntityType(entityTypeFilter);

        Page<Article> articles;
        if (subjectId != null && entityType != null) {
            articles = articleRepository.findBySubjectIdAndEntityType(subjectId, entityType, pageable);
        } else if (subjectId != null) {
            articles = articleRepository.findBySubjectId(subjectId, pageable);
        } else if (entityType != null) {
            articles = articleRepository.findByEntityType(entityType, pageable);
        } else {
            articles = articleRepository.findAll(pageable);
        }

        return articles.getContent().stream().map(this::toResponse).toList();
    }

    // Same reasoning as SubjectService.parseKind: a raw String filter parsed here produces a
    // translated 400 carrying INVALID_ENTITY_TYPE, instead of the generic Spring conversion
    // failure binding the enum type directly on the controller parameter would give.
    private EntityType parseEntityType(String entityTypeFilter) {
        try {
            return EntityType.valueOf(entityTypeFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidEntityTypeException("invalid entity type filter: " + entityTypeFilter);
        }
    }

    // The derivation invariant: entityType has exactly one source, never both. A subject-bound
    // article's entityType is read off the Subject's own kind so the two can never disagree;
    // a subject-less article has no kind to read, so the client must say GENERIC explicitly
    // rather than leaving it to be inferred from absence.
    private EntityType resolveEntityType(UUID subjectId, EntityType requestedEntityType) {
        if (subjectId != null) {
            if (requestedEntityType != null) {
                throw new EntityTypeNotDerivableException("entityType must not be supplied when subjectId is set");
            }
            Subject subject = subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new);
            return EntityType.valueOf(subject.getKind().name());
        }
        if (requestedEntityType != EntityType.GENERIC) {
            throw new EntityTypeNotDerivableException("entityType must be GENERIC when subjectId is not set");
        }
        return requestedEntityType;
    }

    private ArticleResponse toResponse(Article article) {
        return new ArticleResponse(article.getId(), article.getSubjectId(), article.getEntityType(),
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt());
    }
}

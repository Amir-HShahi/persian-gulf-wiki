package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleListItemResponse;
import com.persiangulfwiki.core.article.dto.ArticleResponse;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.DuplicateSlugException;
import com.persiangulfwiki.core.article.exception.EntityTypeNotDerivableException;
import com.persiangulfwiki.core.article.exception.InvalidEntityTypeException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final ArticleRevisionService articleRevisionService;
    private final ArticleVisibilityService articleVisibilityService;
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

        // currentRevisionId is deliberately left null here. It names the revision readers are
        // served, and a revision nobody has reviewed is not that -- only a moderator's APPROVE
        // may point it anywhere (ArticleRevisionService.applyModerationOutcome is the sole
        // writer). Setting it to the freshly created DRAFT, as this originally did, published
        // unmoderated content to anonymous readers the instant an article was created and made
        // the approval step a no-op that re-pointed it where it already pointed.
        articleRevisionService.createInitialRevision(
                translation.getId(), request.title(), request.body(), request.summary(), creatorUserId);

        return toResponse(article);
    }

    // An article nothing has been approved in yet is 404 to anyone but its authors and
    // moderators -- see ArticleVisibilityService.
    @Transactional(readOnly = true)
    public ArticleResponse get(UUID articleId, @Nullable UUID callerUserId, boolean isCallerModerator) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (!articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator)) {
            throw new ArticleNotFoundException();
        }
        return toResponse(article);
    }

    // Only translations with an approved revision are listed: title/summary come from
    // currentRevisionId, which only a moderator's APPROVE sets, so unreviewed text never
    // reaches this public list. One page = three queries (translations, then their articles
    // and revisions by id) rather than one per row.
    @Transactional(readOnly = true)
    public List<ArticleListItemResponse> list(String language, UUID subjectId, String entityTypeFilter, Pageable pageable) {
        EntityType entityType = entityTypeFilter == null ? null : parseEntityType(entityTypeFilter);

        List<ArticleTranslation> translations = articleTranslationRepository
                .findPublishedByLanguage(language, subjectId, entityType, pageable)
                .getContent();

        Map<UUID, Article> articlesById = articleRepository
                .findAllById(translations.stream().map(ArticleTranslation::getArticleId).toList())
                .stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        Map<UUID, ArticleRevision> revisionsById = articleRevisionRepository
                .findAllById(translations.stream().map(ArticleTranslation::getCurrentRevisionId).toList())
                .stream()
                .collect(Collectors.toMap(ArticleRevision::getId, Function.identity()));

        return translations.stream()
                .map(translation -> toListItemResponse(articlesById.get(translation.getArticleId()), translation,
                        revisionsById.get(translation.getCurrentRevisionId())))
                .toList();
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

    private ArticleListItemResponse toListItemResponse(
            Article article, ArticleTranslation translation, ArticleRevision revision) {
        return new ArticleListItemResponse(article.getId(), article.getSubjectId(), article.getEntityType(),
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt(), translation.getLanguage(), translation.getSlug(), revision.getTitle(),
                revision.getSummary(), revision.getId());
    }
}

package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleListItemResponse;
import com.persiangulfwiki.core.article.dto.ArticleResponse;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.DuplicateSlugException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        SubjectKind subjectKind = request.subjectId() == null ? null : findSubjectKind(request.subjectId());

        if (articleTranslationRepository.existsBySlug(request.slug())) {
            throw new DuplicateSlugException();
        }

        Article article = articleRepository.save(Article.builder()
                .subjectId(request.subjectId())
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

        return toResponse(article, subjectKind);
    }

    // An article nothing has been approved in yet is 404 to anyone but its authors and
    // moderators -- see ArticleVisibilityService.
    @Transactional(readOnly = true)
    public ArticleResponse get(UUID articleId, @Nullable UUID callerUserId, boolean isCallerModerator) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (!articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator)) {
            throw new ArticleNotFoundException();
        }
        return toResponse(article, article.getSubjectId() == null ? null : findSubjectKind(article.getSubjectId()));
    }

    // Only translations with an approved revision are listed: title/summary come from
    // currentRevisionId, which only a moderator's APPROVE sets, so unreviewed text never
    // reaches this public list. One page = four queries (translations, then their articles,
    // revisions and subjects by id) rather than one per row.
    @Transactional(readOnly = true)
    public List<ArticleListItemResponse> list(String language, UUID subjectId, String subjectKindFilter, Pageable pageable) {
        SubjectKind subjectKind = subjectKindFilter == null ? null : parseSubjectKind(subjectKindFilter);

        List<ArticleTranslation> translations = articleTranslationRepository
                .findPublishedByLanguage(language, subjectId, subjectKind, pageable)
                .getContent();

        Map<UUID, Article> articlesById = articleRepository
                .findAllById(translations.stream().map(ArticleTranslation::getArticleId).toList())
                .stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        Map<UUID, ArticleRevision> revisionsById = articleRevisionRepository
                .findAllById(translations.stream().map(ArticleTranslation::getCurrentRevisionId).toList())
                .stream()
                .collect(Collectors.toMap(ArticleRevision::getId, Function.identity()));
        Map<UUID, SubjectKind> kindsBySubjectId = subjectRepository
                .findAllById(articlesById.values().stream().map(Article::getSubjectId).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getKind));

        return translations.stream()
                .map(translation -> {
                    Article article = articlesById.get(translation.getArticleId());
                    return toListItemResponse(article, kindsBySubjectId.get(article.getSubjectId()), translation,
                            revisionsById.get(translation.getCurrentRevisionId()));
                })
                .toList();
    }

    // Same reasoning as SubjectService.parseKind, and the same INVALID_SUBJECT_KIND 400, so a
    // client filtering articles and subjects by kind handles one error code for both.
    private SubjectKind parseSubjectKind(String subjectKindFilter) {
        try {
            return SubjectKind.valueOf(subjectKindFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidSubjectKindException("invalid subject kind filter: " + subjectKindFilter);
        }
    }

    private SubjectKind findSubjectKind(UUID subjectId) {
        return subjectRepository.findById(subjectId).map(Subject::getKind).orElseThrow(SubjectNotFoundException::new);
    }

    private ArticleResponse toResponse(Article article, @Nullable SubjectKind subjectKind) {
        return new ArticleResponse(article.getId(), article.getSubjectId(), subjectKind,
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt());
    }

    private ArticleListItemResponse toListItemResponse(
            Article article, @Nullable SubjectKind subjectKind, ArticleTranslation translation, ArticleRevision revision) {
        return new ArticleListItemResponse(article.getId(), article.getSubjectId(), subjectKind,
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt(), translation.getLanguage(), translation.getSlug(), revision.getTitle(),
                revision.getSummary(), revision.getId());
    }
}

package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleBySlugResponse;
import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import com.persiangulfwiki.core.article.dto.ArticleListItemResponse;
import com.persiangulfwiki.core.article.dto.ArticleResponse;
import com.persiangulfwiki.core.article.dto.ArticleSourceResponse;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.PublishedRevisionResponse;
import com.persiangulfwiki.core.article.dto.PublishedTranslationResponse;
import com.persiangulfwiki.core.article.dto.TranslationSlugResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.DuplicateSlugException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.service.SubjectMainImageService;
import com.persiangulfwiki.core.subject.service.SubjectService;
import com.persiangulfwiki.core.topic.service.TopicService;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final ArticleRevisionService articleRevisionService;
    private final ArticleVisibilityService articleVisibilityService;
    private final SubjectRepository subjectRepository;
    private final SubjectService subjectService;
    private final TopicService topicService;
    private final SubjectLinkService subjectLinkService;
    private final ArticleCardService articleCardService;
    private final SubjectMainImageService subjectMainImageService;
    private final ObjectMapper objectMapper;

    // Article + its canonical translation + that translation's first DRAFT revision, written
    // together or not at all: an article with zero translations can never be reached by
    // GET .../translations/{language}, and nothing in this API creates a translation on its
    // own for a brand-new article -- same "detail row must exist or the parent is
    // unaddressable" reasoning as SubjectService.create.
    //
    // A subject that was merged away binds the article to the subject it was merged into; any other
    // deleted subject is a 404, so no article lands on a subject hidden from readers.
    @Transactional
    public ArticleResponse create(UUID creatorUserId, CreateArticleRequest request, String readerLanguage) {
        Subject subject = request.subjectId() == null ? null : subjectService.lockForBinding(request.subjectId());
        SubjectKind subjectKind = subject == null ? null : subject.getKind();

        if (articleTranslationRepository.existsBySlug(request.slug())) {
            throw new DuplicateSlugException();
        }

        Article article = articleRepository.save(Article.builder()
                .subjectId(subject == null ? null : subject.getId())
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
                translation.getId(), request.title(), request.body(), request.summary(), creatorUserId,
                request.topicIds() == null ? Set.of() : request.topicIds());

        return toResponse(article, subjectKind, readerLanguage);
    }

    // An article nothing has been approved in yet is 404 to anyone but its authors and
    // moderators -- see ArticleVisibilityService.
    @Transactional(readOnly = true)
    public ArticleResponse get(UUID articleId, @Nullable UUID callerUserId, boolean isCallerModerator,
            String readerLanguage) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (!articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator)) {
            throw new ArticleNotFoundException();
        }
        return toResponse(article, article.getSubjectId() == null ? null : findSubjectKind(article.getSubjectId()),
                readerLanguage);
    }

    // The reader page, by a translation's slug. Approved content only, whoever asks: a translation
    // with nothing approved is 404 even to its authors and moderators, who read drafts through the
    // id-based routes. Unlike list(), an article about a deleted subject is still served.
    @Transactional(readOnly = true)
    public ArticleBySlugResponse getBySlug(String slug, String readerLanguage) {
        ArticleTranslation translation = articleTranslationRepository.findBySlug(slug)
                .filter(found -> found.getCurrentRevisionId() != null)
                .orElseThrow(ArticleNotFoundException::new);
        Article article = articleRepository.findById(translation.getArticleId())
                .orElseThrow(ArticleNotFoundException::new);
        ArticleRevision revision = articleRevisionRepository.findById(translation.getCurrentRevisionId())
                .orElseThrow(ArticleNotFoundException::new);
        SubjectKind subjectKind = article.getSubjectId() == null ? null
                : subjectRepository.findById(article.getSubjectId()).map(Subject::getKind).orElse(null);
        JsonNode body = objectMapper.readTree(revision.getBody());

        List<TranslationSlugResponse> otherTranslations = articleTranslationRepository
                .findByArticleIdInAndCurrentRevisionIdIsNotNull(List.of(article.getId()))
                .stream()
                .filter(other -> !other.getId().equals(translation.getId()))
                .sorted(Comparator.comparing(ArticleTranslation::getLanguage))
                .map(other -> new TranslationSlugResponse(other.getLanguage(), other.getSlug()))
                .toList();

        return new ArticleBySlugResponse(article.getId(), article.getSubjectId(), subjectKind, article.isMain(),
                article.getCanonicalLanguage(), article.getCreatedAt(), article.getUpdatedAt(),
                new PublishedTranslationResponse(translation.getId(), translation.getLanguage(), translation.getSlug(),
                        translation.getTranslationState(), translation.getUpdatedAt(),
                        translation.getId().equals(article.getSourceTranslationId())),
                mainImage(article),
                new PublishedRevisionResponse(revision.getId(), revision.getRevisionNumber(), revision.getTitle(),
                        revision.getSummary(), body, revision.getCreatedAt(), revision.isMachineGenerated(),
                        revision.getMachineModel()),
                subjectLinkService.resolve(List.of(body), readerLanguage),
                articleRevisionService.liveTopicIds(article.getId()),
                otherTranslations, source(article, translation));
    }

    // Only a published source is named: an unpublished one has no page to link to.
    private @Nullable ArticleSourceResponse source(Article article, ArticleTranslation shown) {
        UUID sourceId = article.getSourceTranslationId();
        if (sourceId == null) {
            return null;
        }
        ArticleTranslation source = sourceId.equals(shown.getId()) ? shown
                : articleTranslationRepository.findById(sourceId).orElse(null);
        return source == null || source.getCurrentRevisionId() == null ? null
                : new ArticleSourceResponse(source.getId(), source.getLanguage(), source.getSlug());
    }

    // Only translations with an approved revision are listed: title/summary come from
    // currentRevisionId, which only a moderator's APPROVE sets, so unreviewed text never
    // reaches this public list. One page = four queries (translations, then their articles,
    // revisions and subjects by id) rather than one per row. A merged-away subjectId filters on the
    // subject it was merged into, which now holds its articles. An unknown or archived topic slug
    // matches nothing -- an empty page, like an unknown subjectId -- rather than a 400.
    @Transactional(readOnly = true)
    public PageResponse<ArticleListItemResponse> list(String language, UUID subjectId, String subjectKindFilter,
            @Nullable String topicSlug, Pageable pageable) {
        SubjectKind subjectKind = subjectKindFilter == null ? null : parseSubjectKind(subjectKindFilter);
        UUID subjectFilter = subjectId == null ? null
                : subjectService.redirectTargets(List.of(subjectId)).getOrDefault(subjectId, subjectId);
        UUID topicId = null;
        if (topicSlug != null) {
            Optional<UUID> found = topicService.findFilterableId(topicSlug);
            if (found.isEmpty()) {
                return new PageResponse<>(List.of(), 0);
            }
            topicId = found.get();
        }

        Page<ArticleTranslation> page = articleTranslationRepository
                .findPublishedByLanguage(language, subjectFilter, subjectKind, topicId, pageable);
        List<ArticleTranslation> translations = page.getContent();

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
        Map<UUID, SubjectMainImageResponse> imagesBySubjectId = subjectMainImageService.mainImages(
                articlesById.values().stream().map(Article::getSubjectId).filter(Objects::nonNull).distinct().toList());

        List<ArticleListItemResponse> items = translations.stream()
                .map(translation -> {
                    Article article = articlesById.get(translation.getArticleId());
                    return toListItemResponse(article, kindsBySubjectId.get(article.getSubjectId()), translation,
                            revisionsById.get(translation.getCurrentRevisionId()),
                            article.getSubjectId() == null ? null : imagesBySubjectId.get(article.getSubjectId()));
                })
                .toList();
        return new PageResponse<>(items, page.getTotalElements());
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

    private @Nullable SubjectMainImageResponse mainImage(Article article) {
        UUID subjectId = article.getSubjectId();
        return subjectId == null ? null : subjectMainImageService.mainImages(List.of(subjectId)).get(subjectId);
    }

    private SubjectKind findSubjectKind(UUID subjectId) {
        return subjectRepository.findById(subjectId).map(Subject::getKind).orElseThrow(SubjectNotFoundException::new);
    }

    private ArticleResponse toResponse(Article article, @Nullable SubjectKind subjectKind, String readerLanguage) {
        ArticleCardResponse card = articleCardService.cardsOf(List.of(article), readerLanguage).get(article.getId());
        return new ArticleResponse(article.getId(), article.getSubjectId(), subjectKind,
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt(), card.language(), card.slug(), card.title(), card.summary(), card.revisionId(),
                card.mainImage());
    }

    private ArticleListItemResponse toListItemResponse(
            Article article, @Nullable SubjectKind subjectKind, ArticleTranslation translation,
            ArticleRevision revision, @Nullable SubjectMainImageResponse mainImage) {
        return new ArticleListItemResponse(article.getId(), article.getSubjectId(), subjectKind,
                article.getCanonicalLanguage(), article.getCreatedByUserId(), article.getCreatedAt(),
                article.getUpdatedAt(), translation.getLanguage(), translation.getSlug(), revision.getTitle(),
                revision.getSummary(), revision.getId(), mainImage);
    }
}

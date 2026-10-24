package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import com.persiangulfwiki.core.article.dto.CreateFeaturedArticleRequest;
import com.persiangulfwiki.core.article.dto.CurrentFeaturedArticleResponse;
import com.persiangulfwiki.core.article.dto.FeaturedArticleResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.FeaturedArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.InvalidFeaturedArticleWindowException;
import com.persiangulfwiki.core.article.exception.RevisionNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository.LiveFeature;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.service.SubjectMainImageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// The home page's featured article. Admins schedule windows; the reader gets the article of the
// latest-started live window that is published in their language, else the newest published
// article in it, so the card is never empty while anything is published. Who may schedule is
// decided by the admin-only routes in FeaturedArticleController.
@Slf4j
@Service
@RequiredArgsConstructor
public class FeaturedArticleService {

    private static final Pageable FIRST = PageRequest.of(0, 1);

    private final FeaturedArticleRepository featuredArticleRepository;
    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectMainImageService subjectMainImageService;
    private final ArticleCardService articleCardService;

    /** Throws FeaturedArticleNotFoundException when nothing at all is published in {@code language}. */
    @Transactional(readOnly = true)
    public CurrentFeaturedArticleResponse getCurrent(String language) {
        List<LiveFeature> live = featuredArticleRepository.findLive(language, Instant.now(), FIRST);
        UUID featuredArticleId = live.isEmpty() ? null : live.getFirst().getFeaturedArticleId();
        ArticleTranslation translation = live.isEmpty()
                ? articleTranslationRepository.findPublishedByLanguage(language, null, null, null, FIRST).stream()
                        .findFirst()
                        .orElseThrow(FeaturedArticleNotFoundException::new)
                : articleTranslationRepository.findById(live.getFirst().getTranslationId())
                        .orElseThrow(FeaturedArticleNotFoundException::new);

        Article article = articleRepository.findById(translation.getArticleId())
                .orElseThrow(ArticleNotFoundException::new);
        ArticleRevision revision = articleRevisionRepository.findById(translation.getCurrentRevisionId())
                .orElseThrow(RevisionNotFoundException::new);
        UUID subjectId = article.getSubjectId();
        return new CurrentFeaturedArticleResponse(article.getId(), subjectId,
                subjectId == null ? null : subjectRepository.findById(subjectId).map(Subject::getKind).orElse(null),
                translation.getLanguage(), translation.getSlug(), revision.getTitle(), revision.getSummary(),
                revision.getId(),
                subjectId == null ? null : subjectMainImageService.mainImages(Set.of(subjectId)).get(subjectId),
                featuredArticleId);
    }

    @Transactional(readOnly = true)
    public PageResponse<FeaturedArticleResponse> list(Pageable pageable, String readerLanguage) {
        Page<FeaturedArticle> page = featuredArticleRepository.findAllByOrderByStartsAtDescIdAsc(pageable);
        Map<UUID, ArticleCardResponse> cards = articleCardService.cards(
                page.getContent().stream().map(FeaturedArticle::getArticleId).distinct().toList(), readerLanguage);
        return new PageResponse<>(
                page.getContent().stream().map(window -> toResponse(window, cards.get(window.getArticleId()))).toList(),
                page.getTotalElements());
    }

    // An article with nothing approved yet may be scheduled -- the window simply shows nothing
    // until it is published, so scheduling ahead of approval works.
    @Transactional
    public FeaturedArticleResponse create(UUID adminUserId, CreateFeaturedArticleRequest request,
            String readerLanguage) {
        Instant startsAt = Objects.requireNonNullElseGet(request.startsAt(), Instant::now);
        if (request.endsAt() != null && !request.endsAt().isAfter(startsAt)) {
            throw new InvalidFeaturedArticleWindowException();
        }
        ArticleCardResponse card = articleCardService.card(request.articleId(), readerLanguage);
        FeaturedArticle window = featuredArticleRepository.save(FeaturedArticle.builder()
                .articleId(request.articleId())
                .startsAt(startsAt)
                .endsAt(request.endsAt())
                .featuredByUserId(adminUserId)
                .build());
        log.info("admin {} featured article {} from {} (window {})", adminUserId, request.articleId(), startsAt,
                window.getId());
        return toResponse(window, card);
    }

    @Transactional
    public void delete(UUID adminUserId, UUID featuredArticleId) {
        FeaturedArticle window = featuredArticleRepository.findById(featuredArticleId)
                .orElseThrow(FeaturedArticleNotFoundException::new);
        featuredArticleRepository.delete(window);
        log.info("admin {} removed featured-article window {}", adminUserId, featuredArticleId);
    }

    private static FeaturedArticleResponse toResponse(FeaturedArticle window, ArticleCardResponse article) {
        return new FeaturedArticleResponse(window.getId(), article, window.getStartsAt(),
                window.getEndsAt(), window.getFeaturedByUserId(), window.getCreatedAt());
    }
}

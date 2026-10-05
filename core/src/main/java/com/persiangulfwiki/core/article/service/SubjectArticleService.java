package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.SubjectArticleResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.InvalidMainArticleException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository.MainArticleSlug;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// A subject's articles and which one is main. The main article's slug is the subject's page URL,
// and its gallery feeds the subject's 360° tour (decision 9).
//
// The rule: a subject's first article to get an approved translation becomes main, and a moderator
// may switch it to any of the subject's other published articles. Every write here holds the subject
// row lock (the one subject revision approval takes), so two first approvals can't both see "no main
// yet"; uq_articles_main_per_subject (V28) is only the backstop.
@Service
@RequiredArgsConstructor
public class SubjectArticleService {

    private static final String FARSI = SupportedLocales.FARSI.getLanguage();

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final AuditLogService auditLogService;

    // Public: only articles with an approved translation are listed, each through one of them.
    @Transactional(readOnly = true)
    public List<SubjectArticleResponse> list(UUID subjectId, String language, Pageable pageable) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new SubjectNotFoundException();
        }
        return toResponses(articleRepository.findPublishedBySubjectId(subjectId, pageable), language);
    }

    /**
     * Makes {@code articleId} the subject's main article and demotes the previous one. Choosing the
     * article that is already main changes nothing and is not audit-logged.
     *
     * @throws InvalidMainArticleException if the article does not exist, is about another subject or
     *         has no approved translation
     */
    @Transactional
    public SubjectArticleResponse switchMain(UUID moderatorId, UUID subjectId, UUID articleId, String language) {
        subjectRepository.findByIdForUpdate(subjectId).orElseThrow(SubjectNotFoundException::new);
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new InvalidMainArticleException("no article " + articleId,
                        "validation.mainArticle.notFound"));
        if (!subjectId.equals(article.getSubjectId())) {
            throw new InvalidMainArticleException("article " + articleId + " is not about subject " + subjectId,
                    "validation.mainArticle.otherSubject");
        }
        if (!articleTranslationRepository.existsByArticleIdAndCurrentRevisionIdIsNotNull(articleId)) {
            throw new InvalidMainArticleException("article " + articleId + " has no approved translation",
                    "validation.mainArticle.unpublished");
        }

        if (!article.isMain()) {
            Article previous = articleRepository.findMainBySubjectId(subjectId).orElse(null);
            if (previous != null) {
                previous.setMain(false);
                // Flushed before the new main is written: the partial unique index is checked per
                // statement, so the two rows must never both hold the flag.
                articleRepository.saveAndFlush(previous);
            }
            article.setMain(true);
            articleRepository.save(article);
            auditLogService.record(moderatorId, "SUBJECT_MAIN_ARTICLE_SWITCH", null, "subjectId=" + subjectId
                    + ", from=" + (previous == null ? "none" : previous.getId()) + ", to=" + articleId);
            // Infoboxes and cards link subjects by their main article's slug.
            subjectInfoboxCacheService.evictAll();
        }
        return toResponses(List.of(article), language).getFirst();
    }

    // Called when one of the article's translations is approved for the first time. Makes the
    // article main if its subject has none yet; a subject-less or already-main article is left alone.
    @Transactional
    public void promoteIfSubjectHasNoMain(UUID articleId) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (article.getSubjectId() == null || article.isMain()) {
            return;
        }
        subjectRepository.findByIdForUpdate(article.getSubjectId()).orElseThrow(SubjectNotFoundException::new);
        if (articleRepository.findMainBySubjectId(article.getSubjectId()).isEmpty()) {
            article.setMain(true);
            articleRepository.save(article);
        }
    }

    /**
     * Gives every subject left without a main article (its main one was deleted) its oldest article
     * with an approved translation. Only the dev fixture routes delete articles today. Clears the
     * persistence context, so callers must not reuse entities loaded before it.
     */
    @Transactional
    public void promoteWhereMissing() {
        if (articleRepository.promoteMainArticlesWhereMissing() > 0) {
            subjectInfoboxCacheService.evictAll();
        }
    }

    /**
     * Slug of each subject's main article in {@code language}, else in Farsi. A subject with no main
     * article, or whose main article is published in neither language, is absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> mainArticleSlugs(Collection<UUID> subjectIds, String language) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> slugs = new HashMap<>();
        List<String> languages = Stream.of(language, FARSI).distinct().toList();
        for (MainArticleSlug row : articleTranslationRepository.findMainArticleSlugs(subjectIds, languages)) {
            // The reader's language always wins; Farsi only stands in until it is found.
            if (row.getLanguage().equals(language) || !slugs.containsKey(row.getSubjectId())) {
                slugs.put(row.getSubjectId(), row.getSlug());
            }
        }
        return slugs;
    }

    // Every article passed in must have an approved translation. Three queries for the whole page.
    private List<SubjectArticleResponse> toResponses(List<Article> articles, String language) {
        if (articles.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ArticleTranslation>> translationsByArticle = articleTranslationRepository
                .findByArticleIdInAndCurrentRevisionIdIsNotNull(articles.stream().map(Article::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ArticleTranslation::getArticleId));
        Map<UUID, ArticleTranslation> shown = articles.stream()
                .collect(Collectors.toMap(Article::getId, article -> pick(
                        translationsByArticle.get(article.getId()), article.getCanonicalLanguage(), language)));
        Map<UUID, ArticleRevision> revisions = articleRevisionRepository
                .findAllById(shown.values().stream().map(ArticleTranslation::getCurrentRevisionId).toList())
                .stream()
                .collect(Collectors.toMap(ArticleRevision::getId, Function.identity()));

        return articles.stream()
                .map(article -> {
                    ArticleTranslation translation = shown.get(article.getId());
                    ArticleRevision revision = revisions.get(translation.getCurrentRevisionId());
                    return new SubjectArticleResponse(article.getId(), article.isMain(), translation.getLanguage(),
                            !translation.getLanguage().equals(language), translation.getSlug(), revision.getTitle(),
                            revision.getSummary(), revision.getId());
                })
                .toList();
    }

    // The reader's language, else Farsi, else the article's own language, else the first by code.
    private static ArticleTranslation pick(List<ArticleTranslation> translations, String canonicalLanguage,
            String language) {
        // false sorts before true, so each "is not X" key puts X first.
        Comparator<ArticleTranslation> preference = Comparator
                .comparing((ArticleTranslation translation) -> !translation.getLanguage().equals(language))
                .thenComparing(translation -> !translation.getLanguage().equals(FARSI))
                .thenComparing(translation -> !translation.getLanguage().equals(canonicalLanguage))
                .thenComparing(ArticleTranslation::getLanguage);
        return translations.stream().min(preference).orElseThrow();
    }
}

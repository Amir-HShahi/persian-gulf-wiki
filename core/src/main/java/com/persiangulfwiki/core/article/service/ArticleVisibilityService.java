package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Read-authorization for articles and translations that have not been published yet -- the
// container-level counterpart of ArticleRevisionService.isReadableBy, which covers individual
// revisions. "Published" means a moderator has approved something: a translation is published
// once it has a currentRevisionId (only an APPROVE sets it), an article once any of its
// translations is. Until then only the people who wrote it and moderators (ADMIN included, via
// the role hierarchy the caller resolves) may see it; to everyone else it does not exist, and
// callers throw the matching *NotFoundException (404), never 403 -- same deliberate choice as
// hidden revisions.
//
// Depends only on repositories, so every article service can use it without a cycle. Both
// checks fail closed: an anonymous caller (null id) matches no author.
@Service
@RequiredArgsConstructor
public class ArticleVisibilityService {

    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;

    // Authors are "anyone who wrote a revision of this translation", not just revision 1's --
    // ArticleTranslation carries no creator of its own, and anyone who can already read one of
    // their own revisions through isReadableBy must be able to reach the translation it lives in.
    @Transactional(readOnly = true)
    public boolean isTranslationReadableBy(ArticleTranslation translation, @Nullable UUID callerUserId,
            boolean isCallerModerator) {
        return translation.getCurrentRevisionId() != null
                || isCallerModerator
                || (callerUserId != null
                        && articleRevisionRepository.existsByTranslationIdAndAuthorId(translation.getId(), callerUserId));
    }

    // Authors are the article's creator plus anyone who wrote a revision in any of its
    // translations, so a translator can still reach the article they added a language to.
    // createdByUserId is null once the creator's account is deleted; equals(null) is false, so
    // that simply removes one author rather than opening the article up.
    @Transactional(readOnly = true)
    public boolean isArticleReadableBy(Article article, @Nullable UUID callerUserId, boolean isCallerModerator) {
        if (isCallerModerator
                || articleTranslationRepository.existsByArticleIdAndCurrentRevisionIdIsNotNull(article.getId())) {
            return true;
        }
        if (callerUserId == null) {
            return false;
        }
        return callerUserId.equals(article.getCreatedByUserId())
                || articleRevisionRepository.existsInArticleByAuthor(article.getId(), callerUserId);
    }
}

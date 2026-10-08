package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestArticleResponse;
import com.persiangulfwiki.core.dev.exception.DevTestMainArticleTakenException;
import com.persiangulfwiki.core.dev.exception.InvalidDevTestArticleRequestException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.topic.service.TopicService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

// The one place a marked article + canonical translation + first revision is minted, shared by
// DevTestArticleController and by the media and moderation fixture controllers, which mint a
// throwaway article themselves when the caller names no target -- so a suite for either never
// has to set an article up first. Every row it writes carries the same dev marker as an article
// minted through the article endpoint, so DevTestArticleSweeper reclaims them all alike.
//
// Same gating as the controllers: @Profile("dev"), so the bean does not exist elsewhere. It
// bypasses ArticleService/ArticleRevisionService on purpose -- see DevTestArticleController for
// why (revision status is taken as-is, including states the real flow cannot reach).
@Component
@Profile("dev")
@RequiredArgsConstructor
class DevTestArticleFixtures {

    // Enough of the UUID to keep parallel runs from producing identical slugs/usernames in a
    // failing test's output, short enough to stay readable there.
    private static final int SLUG_LENGTH = 12;

    // A minimal but valid structured-content document, matching the shape Phase 4's renderer
    // expects to walk (see the JSON-mapping decision comment on ArticleRevision.body) --
    // exact structure doesn't matter for a fixture, only that it is valid JSON text, since the
    // column is jsonb.
    private static final String MINIMAL_BODY_JSON = "{\"type\":\"doc\",\"content\":[]}";

    private static final String DEFAULT_LANGUAGE = "fa";

    // DevUserSeeder's stable contributor fixture. Deliberately a seeded account rather than a
    // freshly minted one -- see resolveAuthorUserId for the sweep-ordering race that choice
    // avoids.
    private static final String SEEDED_AUTHOR_EMAIL = "contributor@dev.local";

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final TopicService topicService;
    private final ObjectMapper objectMapper;

    DevTestArticleResponse mint(DevTestArticleRequest request) {
        String slug = request.slug() != null ? request.slug() : DevTestFixtures.MARKER + "-article-" + randomSlug();
        String canonicalLanguage = request.canonicalLanguage() != null ? request.canonicalLanguage() : DEFAULT_LANGUAGE;
        RevisionStatus revisionStatus = request.revisionStatus() != null ? request.revisionStatus() : RevisionStatus.DRAFT;
        TranslationState translationState =
                request.translationState() != null ? request.translationState() : TranslationState.UP_TO_DATE;
        String title = request.title() != null ? request.title() : "e2e article " + slug;
        String summary = request.summary() != null ? request.summary() : "e2e summary";

        // Checked up front so an unknown subject is the documented 404, not a foreign-key
        // violation surfacing as a 409 from the insert below.
        if (request.subjectId() != null && !subjectRepository.existsById(request.subjectId())) {
            throw new SubjectNotFoundException();
        }
        Set<UUID> topicIds = request.topicIds() != null ? request.topicIds() : Set.of();
        // The same check as the API, archived topics included -- a deliberate exception to fixtures
        // bypassing the service path (decided with the user): a suite reaches "revision keeps an
        // archived topic" by minting the article first, then archiving through PATCH
        // /api/admin/topics/{id}.
        topicService.requireAssignable(topicIds, Set.of());
        UUID authorUserId = resolveAuthorUserId(request.authorUserId());
        boolean isMain = resolveIsMain(request, revisionStatus);

        Article article = articleRepository.save(Article.builder()
                .subjectId(request.subjectId())
                .canonicalLanguage(canonicalLanguage)
                .createdByUserId(authorUserId)
                .isMain(isMain)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        ArticleTranslation translation = articleTranslationRepository.save(ArticleTranslation.builder()
                .articleId(article.getId())
                .language(canonicalLanguage)
                .slug(slug)
                .translationState(translationState)
                .build());

        ArticleRevision revision = ArticleRevision.builder()
                .translationId(translation.getId())
                .revisionNumber(1)
                .parentRevisionId(null)
                .title(title)
                .body(request.body() != null ? objectMapper.writeValueAsString(request.body()) : MINIMAL_BODY_JSON)
                .summary(summary)
                .status(revisionStatus)
                .authorId(authorUserId)
                .build();
        revision.setTopicIds(topicIds);
        revision = articleRevisionRepository.save(revision);

        // Mirrors the production invariant rather than the old unconditional assignment: a
        // translation's currentRevisionId names approved content only (see
        // ArticleTranslation.currentRevisionId). A fixture minted at DRAFT/PENDING/REJECTED
        // must therefore look unpublished, or a suite asserting "approval is what publishes"
        // would pass against a fixture that was never approved.
        //
        // Set directly rather than through the moderation flow, which is the point of the
        // article endpoint: an APPROVED revision that is genuinely published, with no
        // ModerationTask or decision history behind it, is not reachable any other way.
        if (revisionStatus == RevisionStatus.APPROVED) {
            translation.setCurrentRevisionId(revision.getId());
            articleTranslationRepository.save(translation);
        }

        return new DevTestArticleResponse(article.getId(), translation.getId(), revision.getId(), canonicalLanguage,
                slug, revisionStatus, isMain);
    }

    // Null applies the real rule (SubjectArticleService.promoteIfSubjectHasNoMain): an APPROVED
    // article is main when its subject has none yet. True never demotes an existing main article,
    // which may not be one this fixture minted.
    private boolean resolveIsMain(DevTestArticleRequest request, RevisionStatus revisionStatus) {
        UUID subjectId = request.subjectId();
        if (Boolean.FALSE.equals(request.isMain())) {
            return false;
        }
        if (subjectId == null) {
            if (Boolean.TRUE.equals(request.isMain())) {
                throw new InvalidDevTestArticleRequestException("a main article needs a subject");
            }
            return false;
        }
        if (request.isMain() == null && revisionStatus != RevisionStatus.APPROVED) {
            return false;
        }
        // The lock real approvals take, so a concurrent one can't also make an article main.
        subjectRepository.findByIdForUpdate(subjectId);
        boolean hasMain = articleRepository.findMainBySubjectId(subjectId).isPresent();
        if (hasMain && Boolean.TRUE.equals(request.isMain())) {
            throw new DevTestMainArticleTakenException();
        }
        return !hasMain;
    }

    // article_revisions.author_id is NOT NULL with no ON DELETE action (V16) -- inserting an
    // arbitrary or fabricated UUID here would fail the foreign key at insert time, not
    // silently produce a dangling reference. Rejecting an omitted authorUserId outright would
    // need a new exception class, a GlobalExceptionHandler entry and a translated message
    // (per exception-handling.md / translating.md) for a caller that, in the common case,
    // doesn't care who authored the fixture at all.
    //
    // So an omitted author falls back to DevUserSeeder's contributor@dev.local, and that
    // choice is load-bearing rather than arbitrary: a seeded account carries no e2e- prefix,
    // so DevTestUserSweeper can never reclaim it (see DevTestUsers for why the prefix, not
    // the @dev.local domain, is what separates the two populations). Minting a fresh
    // disposable user here instead would be the obvious move and is the wrong one -- that
    // user and this article are created in the same instant with the same TTL, but
    // DevTestArticleSweeper runs at :20 and DevTestUserSweeper at :30, so the user sweep's
    // threshold is ten minutes more permissive than the article sweep's. A pair created
    // inside that ten-minute band has its user swept while its article survives, and
    // author_id's RESTRICT then aborts the *entire* user sweep pass, not just the blocked
    // row. Pointing at a never-swept account removes that race rather than timing around it.
    //
    // The disposable-mint path below survives only for the case where the seeded account is
    // genuinely absent (someone deleted it by hand), so this endpoint still works rather than
    // failing on a missing fixture.
    UUID resolveAuthorUserId(UUID requestedAuthorUserId) {
        if (requestedAuthorUserId != null) {
            return requestedAuthorUserId;
        }
        return userRepository.findByEmail(SEEDED_AUTHOR_EMAIL)
                .map(User::getId)
                .orElseGet(this::mintDisposableAuthor);
    }

    private UUID mintDisposableAuthor() {
        String slug = randomSlug();
        User author = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .enabled(true)
                .emailVerified(true)
                .build());
        return author.getId();
    }

    private String randomSlug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
    }
}

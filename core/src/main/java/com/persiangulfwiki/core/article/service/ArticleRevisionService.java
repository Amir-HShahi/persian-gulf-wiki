package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.RevisionResponse;
import com.persiangulfwiki.core.article.dto.SubjectLinkResponse;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.event.RevisionSubmittedEvent;
import com.persiangulfwiki.core.article.exception.NotRevisionAuthorException;
import com.persiangulfwiki.core.article.exception.RevisionNotEditableException;
import com.persiangulfwiki.core.article.exception.RevisionNotFoundException;
import com.persiangulfwiki.core.article.exception.TranslationNotFoundException;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;
import com.persiangulfwiki.core.topic.exception.InvalidTopicIdsException;
import com.persiangulfwiki.core.topic.service.TopicService;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ArticleRevisionService {

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final ArticleVisibilityService articleVisibilityService;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final SubjectLinkService subjectLinkService;
    private final SubjectArticleService subjectArticleService;
    private final TopicService topicService;

    // Converts JsonNode (every DTO's shape for `body`) to/from the pre-serialized JSON-text
    // String the entity actually stores. See the JSON-mapping decision comment on
    // ArticleRevision.body for why the entity is String rather than JsonNode.
    private final ObjectMapper objectMapper;

    // Lets submit() announce that a revision is now awaiting review without this package
    // knowing that a moderation package exists -- see RevisionSubmittedEvent for why the
    // notification has to run in this direction, and why the listener is synchronous.
    private final ApplicationEventPublisher eventPublisher;

    // The sole place a new ArticleRevision row is ever inserted -- both ArticleService.create
    // and ArticleTranslationService.addTranslation call this rather than building the entity
    // themselves, so the JSON conversion above and the revision-number allocation below never
    // have a second implementation to drift out of sync with this one.
    //
    // topicIds must be empty unless translationId is the article's canonical translation; the
    // caller knows which it is creating. A first revision has no topics yet, so none may be
    // archived. A later path creating a revision from an existing one should pass that one's
    // topics as already on it, as update() does.
    @Transactional
    public UUID createInitialRevision(UUID translationId, String title, JsonNode body, String summary, UUID authorId,
            Set<UUID> topicIds) {
        subjectLinkService.validate(body);
        topicService.requireAssignable(topicIds, Set.of());
        ArticleRevision revision = ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(allocateNextRevisionNumber(translationId))
                .parentRevisionId(null)
                .title(title)
                .body(toJson(body))
                .summary(summary)
                .status(RevisionStatus.DRAFT)
                .authorId(authorId)
                .build();
        revision.setTopicIds(topicIds);
        return articleRevisionRepository.save(revision).getId();
    }

    // Owned here rather than duplicated at each creation call site, even though every Phase 2
    // caller creates a translation's first revision and this always resolves to 1 -- a single
    // source of truth for the allocation rule is what lets a later phase add a second creation
    // path (e.g. resubmitting a REJECTED revision as a new one) without redefining "next
    // number" twice.
    private int allocateNextRevisionNumber(UUID translationId) {
        return articleRevisionRepository.findMaxRevisionNumber(translationId) + 1;
    }

    // Mutates the DRAFT/CHANGES_REQUESTED row in place -- PATCH targets this exact
    // revisionId, so the response must describe the same resource, not a newly-inserted one.
    // Every other status is rejected by the editability check below before this is reached.
    @Transactional
    public RevisionResponse update(UUID callerUserId, UUID articleId, String language, UUID revisionId,
            UpdateRevisionRequest request, String readerLanguage) {
        ArticleTranslation translation = getTranslation(articleId, language);
        ArticleRevision revision = getRevisionScoped(translation, revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);
        subjectLinkService.validate(request.body());
        Set<UUID> topicIds = request.topicIds();
        if (topicIds != null) {
            if (!topicIds.isEmpty() && !isCanonical(translation)) {
                throw new InvalidTopicIdsException("topics on a non-canonical revision " + revisionId,
                        "validation.topicIds.canonicalOnly");
            }
            topicService.requireAssignable(topicIds, revision.getTopicIds());
            revision.setTopicIds(topicIds);
        }

        revision.setTitle(request.title());
        revision.setBody(toJson(request.body()));
        revision.setSummary(request.summary());

        return toResponse(articleRevisionRepository.save(revision), readerLanguage);
    }

    // Silently drops revisions the caller may not read (see isReadableBy) rather than
    // rejecting the whole request, so a public reader still gets the APPROVED history and
    // learns nothing about whether unapproved revisions exist. A translation the caller may
    // not read at all is a 404, not an empty list -- 200 [] would confirm it exists.
    @Transactional(readOnly = true)
    public List<RevisionResponse> list(UUID articleId, String language, @Nullable UUID callerUserId,
            boolean isCallerModerator, String readerLanguage) {
        ArticleTranslation translation = getTranslation(articleId, language);
        if (!articleVisibilityService.isTranslationReadableBy(translation, callerUserId, isCallerModerator)) {
            throw new TranslationNotFoundException();
        }
        List<ArticleRevision> revisions = articleRevisionRepository
                .findByTranslationIdOrderByRevisionNumberAsc(translation.getId()).stream()
                .filter(revision -> isReadableBy(revision, callerUserId, isCallerModerator))
                .toList();
        Map<UUID, JsonNode> bodies = new HashMap<>();
        revisions.forEach(revision -> bodies.put(revision.getId(), fromJson(revision.getBody())));
        // One lookup for every revision's links rather than one per revision.
        Map<UUID, SubjectLinkResponse> links = subjectLinkService.resolve(bodies.values(), readerLanguage);
        return revisions.stream()
                .map(revision -> {
                    JsonNode body = bodies.get(revision.getId());
                    return toResponse(revision, body, subjectLinkService.retainLinkedFrom(body, links));
                })
                .toList();
    }

    // A revision the caller may not read throws RevisionNotFoundException (404), not a
    // 401/403 -- a deliberate project decision, so the single GET hides a hidden revision's
    // existence the same way list() does. Don't "fix" this to 403.
    @Transactional(readOnly = true)
    public RevisionResponse get(UUID articleId, String language, UUID revisionId, @Nullable UUID callerUserId,
            boolean isCallerModerator, String readerLanguage) {
        ArticleRevision revision = getRevisionScoped(articleId, language, revisionId);
        if (!isReadableBy(revision, callerUserId, isCallerModerator)) {
            throw new RevisionNotFoundException();
        }
        return toResponse(revision, readerLanguage);
    }

    // Moves DRAFT|CHANGES_REQUESTED -> PENDING, then announces it.
    //
    // Phase 3 resolved the TODO that used to sit here (it asked for task creation on a first
    // submit and task reopen on a resubmit after REQUEST_CHANGES, without inverting the
    // package dependency) by publishing RevisionSubmittedEvent instead of calling into
    // moderation: moderation listens, and this package still knows nothing about it. The
    // listener is synchronous and runs inside this transaction, so a revision cannot end up
    // PENDING with no moderation task behind it. Read RevisionSubmittedEvent before changing
    // any of that.
    @Transactional
    public RevisionResponse submit(UUID callerUserId, UUID articleId, String language, UUID revisionId,
            String readerLanguage) {
        ArticleRevision revision = getRevisionScoped(articleId, language, revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);

        // Captured before the write: DRAFT means nobody has ever reviewed this revision,
        // CHANGES_REQUESTED means a moderator sent it back and the author has now fixed it.
        // Those are the two branches the listener has to tell apart, and after setStatus they
        // are indistinguishable.
        boolean firstSubmission = revision.getStatus() == RevisionStatus.DRAFT;

        revision.setStatus(RevisionStatus.PENDING);
        ArticleRevision saved = articleRevisionRepository.save(revision);

        eventPublisher.publishEvent(new RevisionSubmittedEvent(saved.getId(), firstSubmission));

        return toResponse(saved, readerLanguage);
    }

    // Read-only status probe for the moderation package, which must refuse to record a
    // decision on a revision that is not actually awaiting review. Exposed as a narrow
    // accessor rather than letting moderation reach for ArticleRevisionRepository directly,
    // so this package keeps sole ownership of its own persistence.
    @Transactional(readOnly = true)
    public RevisionStatus getStatus(UUID revisionId) {
        return articleRevisionRepository.findById(revisionId)
                .orElseThrow(RevisionNotFoundException::new)
                .getStatus();
    }

    // The write half of the same boundary: moderation decides the outcome, this package
    // applies it. Called only from ModerationService, and only with APPROVED, REJECTED or
    // CHANGES_REQUESTED -- a decision can never produce DRAFT or PENDING.
    //
    // APPROVE is the one outcome that does more than move a status: an approved revision
    // becomes what readers of that translation actually see, which means pointing the
    // translation's currentRevisionId at it. That pointer move belongs here rather than in
    // moderation for the same reason getStatus does -- article_translations is this
    // package's table.
    //
    // Note what deliberately does NOT happen on approval: sibling-language translations are
    // not flipped to OUTDATED. That cascade is an explicitly deferred design question (what
    // exactly isOutdated() diffs against was never settled), not an oversight -- see the
    // ArticleTranslation entity.
    @Transactional
    public void applyModerationOutcome(UUID revisionId, RevisionStatus outcome) {
        ArticleRevision revision = articleRevisionRepository.findById(revisionId)
                .orElseThrow(RevisionNotFoundException::new);
        revision.setStatus(outcome);
        articleRevisionRepository.save(revision);

        if (outcome != RevisionStatus.APPROVED) {
            return;
        }

        ArticleTranslation translation = articleTranslationRepository.findById(revision.getTranslationId())
                .orElseThrow(TranslationNotFoundException::new);
        // A translation's first approval can make its article a subject's main article, or give it
        // a slug in another language; cached infoboxes link subjects by that slug.
        boolean isFirstApproval = translation.getCurrentRevisionId() == null;
        translation.setCurrentRevisionId(revision.getId());
        articleTranslationRepository.save(translation);
        if (isFirstApproval) {
            subjectArticleService.promoteIfSubjectHasNoMain(translation.getArticleId());
            subjectInfoboxCacheService.evictAll();
        }
    }

    /**
     * The article's live topics: those of its canonical translation's approved revision, which every
     * translation inherits. Empty while that translation has nothing approved.
     */
    @Transactional(readOnly = true)
    public List<UUID> liveTopicIds(UUID articleId) {
        String canonicalLanguage = articleRepository.findById(articleId)
                .orElseThrow(ArticleNotFoundException::new)
                .getCanonicalLanguage();
        return articleTranslationRepository.findByArticleIdAndLanguage(articleId, canonicalLanguage)
                .map(ArticleTranslation::getCurrentRevisionId)
                .flatMap(articleRevisionRepository::findById)
                .map(revision -> sorted(revision.getTopicIds()))
                .orElse(List.of());
    }

    // The read-authorization rule for revision content: only APPROVED revisions are public;
    // every other status (DRAFT, PENDING, CHANGES_REQUESTED, REJECTED) is visible only to its
    // author and to moderators (ADMIN included, via the role hierarchy the caller resolves).
    // Fails closed: an anonymous caller (null id) matches no author, and a status added to
    // RevisionStatus later is hidden until it is deliberately listed here.
    private boolean isReadableBy(ArticleRevision revision, @Nullable UUID callerUserId, boolean isCallerModerator) {
        return revision.getStatus() == RevisionStatus.APPROVED
                || isCallerModerator
                || revision.getAuthorId().equals(callerUserId);
    }

    private void requireAuthor(ArticleRevision revision, UUID callerUserId) {
        if (!revision.getAuthorId().equals(callerUserId)) {
            throw new NotRevisionAuthorException();
        }
    }

    private void requireEditable(ArticleRevision revision) {
        if (revision.getStatus() != RevisionStatus.DRAFT && revision.getStatus() != RevisionStatus.CHANGES_REQUESTED) {
            throw new RevisionNotEditableException();
        }
    }

    private ArticleTranslation getTranslation(UUID articleId, String language) {
        return articleTranslationRepository.findByArticleIdAndLanguage(articleId, language)
                .orElseThrow(TranslationNotFoundException::new);
    }

    // Scoped through the translation rather than a bare findById: a revisionId that exists
    // but belongs to a different article/language's translation must 404 the same as one
    // that doesn't exist at all, so the (articleId, language, revisionId) triple in the URL
    // is always a consistent resource path. This is URL integrity, NOT access control --
    // revision ids are random v4 UUIDs, and who may read a revision is decided solely by
    // isReadableBy.
    private ArticleRevision getRevisionScoped(UUID articleId, String language, UUID revisionId) {
        return getRevisionScoped(getTranslation(articleId, language), revisionId);
    }

    private ArticleRevision getRevisionScoped(ArticleTranslation translation, UUID revisionId) {
        ArticleRevision revision = articleRevisionRepository.findById(revisionId).orElseThrow(RevisionNotFoundException::new);
        if (!revision.getTranslationId().equals(translation.getId())) {
            throw new RevisionNotFoundException();
        }
        return revision;
    }

    private boolean isCanonical(ArticleTranslation translation) {
        return articleRepository.findById(translation.getArticleId())
                .orElseThrow(ArticleNotFoundException::new)
                .getCanonicalLanguage()
                .equals(translation.getLanguage());
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        return ids.stream().sorted().toList();
    }

    private String toJson(JsonNode node) {
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode fromJson(String json) {
        return objectMapper.readTree(json);
    }

    private RevisionResponse toResponse(ArticleRevision revision, String readerLanguage) {
        JsonNode body = fromJson(revision.getBody());
        return toResponse(revision, body, subjectLinkService.resolve(List.of(body), readerLanguage));
    }

    private RevisionResponse toResponse(ArticleRevision revision, JsonNode body, Map<UUID, SubjectLinkResponse> links) {
        return new RevisionResponse(revision.getId(), revision.getTranslationId(), revision.getRevisionNumber(),
                revision.getParentRevisionId(), revision.getTitle(), body, revision.getSummary(),
                revision.getStatus(), revision.getAuthorId(), revision.getCreatedAt(), links,
                sorted(revision.getTopicIds()));
    }
}

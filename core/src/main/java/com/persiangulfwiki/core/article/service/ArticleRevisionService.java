package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.RevisionResponse;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.exception.NotRevisionAuthorException;
import com.persiangulfwiki.core.article.exception.RevisionNotEditableException;
import com.persiangulfwiki.core.article.exception.RevisionNotFoundException;
import com.persiangulfwiki.core.article.exception.TranslationNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ArticleRevisionService {

    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;

    // Converts JsonNode (every DTO's shape for `body`) to/from the pre-serialized JSON-text
    // String the entity actually stores. See the JSON-mapping decision comment on
    // ArticleRevision.body for why the entity is String rather than JsonNode.
    private final ObjectMapper objectMapper;

    // The sole place a new ArticleRevision row is ever inserted -- both ArticleService.create
    // and ArticleTranslationService.addTranslation call this rather than building the entity
    // themselves, so the JSON conversion above and the revision-number allocation below never
    // have a second implementation to drift out of sync with this one.
    @Transactional
    public UUID createInitialRevision(UUID translationId, String title, JsonNode body, String summary, UUID authorId) {
        ArticleRevision revision = articleRevisionRepository.save(ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(allocateNextRevisionNumber(translationId))
                .parentRevisionId(null)
                .title(title)
                .body(toJson(body))
                .summary(summary)
                .status(RevisionStatus.DRAFT)
                .authorId(authorId)
                .build());
        return revision.getId();
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
            UpdateRevisionRequest request) {
        ArticleRevision revision = getRevisionScoped(articleId, language, revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);

        revision.setTitle(request.title());
        revision.setBody(toJson(request.body()));
        revision.setSummary(request.summary());

        return toResponse(articleRevisionRepository.save(revision));
    }

    @Transactional(readOnly = true)
    public List<RevisionResponse> list(UUID articleId, String language) {
        ArticleTranslation translation = getTranslation(articleId, language);
        return articleRevisionRepository.findByTranslationIdOrderByRevisionNumberAsc(translation.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RevisionResponse get(UUID articleId, String language, UUID revisionId) {
        return toResponse(getRevisionScoped(articleId, language, revisionId));
    }

    // Moves DRAFT|CHANGES_REQUESTED -> PENDING and nothing else.
    //
    // TODO(Phase 3): ModerationService extends this transition with task creation (a first
    // submit, from DRAFT, opens a new ModerationTask) and task reopen (a resubmit after
    // REQUEST_CHANGES, from CHANGES_REQUESTED, reopens the existing one) -- both belong to
    // the moderation package, not here. This method stays the bare status move on purpose:
    // the moderation package depends on article, and wiring task creation into this method
    // would invert that dependency.
    @Transactional
    public RevisionResponse submit(UUID callerUserId, UUID articleId, String language, UUID revisionId) {
        ArticleRevision revision = getRevisionScoped(articleId, language, revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);

        revision.setStatus(RevisionStatus.PENDING);

        return toResponse(articleRevisionRepository.save(revision));
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
    // that doesn't exist at all, not leak another article's content by id guessing.
    private ArticleRevision getRevisionScoped(UUID articleId, String language, UUID revisionId) {
        ArticleTranslation translation = getTranslation(articleId, language);
        ArticleRevision revision = articleRevisionRepository.findById(revisionId).orElseThrow(RevisionNotFoundException::new);
        if (!revision.getTranslationId().equals(translation.getId())) {
            throw new RevisionNotFoundException();
        }
        return revision;
    }

    private String toJson(JsonNode node) {
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode fromJson(String json) {
        return objectMapper.readTree(json);
    }

    private RevisionResponse toResponse(ArticleRevision revision) {
        return new RevisionResponse(revision.getId(), revision.getTranslationId(), revision.getRevisionNumber(),
                revision.getParentRevisionId(), revision.getTitle(), fromJson(revision.getBody()), revision.getSummary(),
                revision.getStatus(), revision.getAuthorId(), revision.getCreatedAt());
    }
}

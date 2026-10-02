package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.source.dto.SourceResponse;
import com.persiangulfwiki.core.source.service.SourceService;
import com.persiangulfwiki.core.subject.dto.CreateSubjectProposalRequest;
import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFieldChangeResponse;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadResponse;
import com.persiangulfwiki.core.subject.dto.SubjectReviewResponse;
import com.persiangulfwiki.core.subject.dto.SubjectRevisionResponse;
import com.persiangulfwiki.core.subject.dto.UpdateSubjectRevisionRequest;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.event.SubjectRevisionSubmittedEvent;
import com.persiangulfwiki.core.subject.exception.NotSubjectRevisionAuthorException;
import com.persiangulfwiki.core.subject.exception.StaleSubjectRevisionException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotEditableException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The write side of a subject: proposals of new subjects and edits of existing ones, both as
// revisions that only a moderator's approval makes live. The lifecycle mirrors article revisions
// (ArticleRevisionService) -- DRAFT and CHANGES_REQUESTED are editable by their author alone,
// submit opens a moderation task through SubjectRevisionSubmittedEvent, and the decision comes
// back through applyModerationOutcome.
//
// What article revisions don't have is a shared target: two edits of one subject both start from
// the live revision, and approving the second after the first would silently undo the first. So
// an edit records its base, and both submit and approval refuse (409) one whose base is no longer
// live -- approval re-checks under the subject row lock, since another edit can be approved
// between this one's submit and its decision. The author then starts a new edit from the current
// revision.
@Service
@RequiredArgsConstructor
public class SubjectRevisionService {

    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectPayloadValidationService subjectPayloadValidationService;
    private final SubjectReadModelService subjectReadModelService;
    private final SourceService sourceService;
    private final ApplicationEventPublisher eventPublisher;

    // Converts the payload records to and from the JSON text SubjectRevision stores -- see
    // ArticleRevision.body for why the entity holds a String.
    private final ObjectMapper objectMapper;

    @Transactional
    public SubjectRevisionResponse propose(UUID authorId, CreateSubjectProposalRequest request) {
        ValidatedSubjectPayload validated = validate(request.kind(), null, request.payload());
        return toResponse(subjectRevisionRepository.save(SubjectRevision.builder()
                .kind(request.kind())
                .revisionNumber(1)
                .payload(toJson(validated.payload()))
                .summary(request.summary())
                .status(SubjectRevisionStatus.DRAFT)
                .authorId(authorId)
                .build()));
    }

    /**
     * Starts an edit as a DRAFT copy of the live payload, unvalidated: it passed when it was approved,
     * and anything the vocabulary has changed since is the author's to fix before submitting.
     */
    @Transactional
    public SubjectRevisionResponse startEdit(UUID authorId, UUID subjectId) {
        Subject subject = lockSubject(subjectId);
        Optional<SubjectRevision> live = findLiveRevision(subjectId);
        String payload = live.map(SubjectRevision::getPayload)
                .orElseGet(() -> toJson(subjectReadModelService.snapshot(subject)));
        return toResponse(subjectRevisionRepository.save(SubjectRevision.builder()
                .subjectId(subjectId)
                .kind(subject.getKind())
                .revisionNumber(subjectRevisionRepository.findMaxRevisionNumber(subjectId) + 1)
                .baseRevisionId(live.map(SubjectRevision::getId).orElse(null))
                .payload(payload)
                .status(SubjectRevisionStatus.DRAFT)
                .authorId(authorId)
                .build()));
    }

    // Replaces payload and summary in place -- PATCH targets this exact revision.
    @Transactional
    public SubjectRevisionResponse update(UUID callerUserId, UUID revisionId, UpdateSubjectRevisionRequest request) {
        SubjectRevision revision = getRevision(revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);

        ValidatedSubjectPayload validated = validate(revision.getKind(), revision.getSubjectId(), request.payload());
        revision.setPayload(toJson(validated.payload()));
        revision.setSummary(request.summary());
        return toResponse(subjectRevisionRepository.save(revision));
    }

    // Staleness is checked before the payload: a stale edit has to be redone from the current
    // revision anyway, so its validation errors would only send the author to fix the wrong copy.
    @Transactional
    public SubjectRevisionResponse submit(UUID callerUserId, UUID revisionId) {
        SubjectRevision revision = getRevision(revisionId);
        requireAuthor(revision, callerUserId);
        requireEditable(revision);
        if (revision.getSubjectId() != null) {
            requireBasedOnLive(revision);
        }
        ValidatedSubjectPayload validated =
                validate(revision.getKind(), revision.getSubjectId(), fromJson(revision.getPayload()));

        boolean firstSubmission = revision.getStatus() == SubjectRevisionStatus.DRAFT;
        revision.setPayload(toJson(validated.payload()));
        revision.setStatus(SubjectRevisionStatus.PENDING);
        SubjectRevision saved = subjectRevisionRepository.save(revision);

        eventPublisher.publishEvent(new SubjectRevisionSubmittedEvent(saved.getId(), firstSubmission));
        return toResponse(saved);
    }

    /** Oldest first, silently leaving out what the caller may not read; a missing subject is a 404. */
    @Transactional(readOnly = true)
    public List<SubjectRevisionResponse> list(UUID subjectId, @Nullable UUID callerUserId,
            boolean isCallerModerator) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new SubjectNotFoundException();
        }
        return subjectRevisionRepository.findBySubjectIdOrderByRevisionNumberAsc(subjectId).stream()
                .filter(revision -> isReadableBy(revision, callerUserId, isCallerModerator))
                .map(this::toResponse)
                .toList();
    }

    /** A revision the caller may not read is a 404, never a 403, so its existence stays hidden. */
    @Transactional(readOnly = true)
    public SubjectRevisionResponse get(UUID revisionId, @Nullable UUID callerUserId, boolean isCallerModerator) {
        SubjectRevision revision = getRevision(revisionId);
        if (!isReadableBy(revision, callerUserId, isCallerModerator)) {
            throw new SubjectRevisionNotFoundException();
        }
        return toResponse(revision);
    }

    // Read-only status probe for the moderation package -- see ArticleRevisionService.getStatus.
    @Transactional(readOnly = true)
    public SubjectRevisionStatus getStatus(UUID revisionId) {
        return getRevision(revisionId).getStatus();
    }

    /**
     * Applies a moderator's decision; called only by ModerationService. APPROVED publishes: for a
     * proposal it creates the subject, and in every case it rewrites the read model and moves the
     * live pointer, all in the caller's transaction. Throws StaleSubjectRevisionException (409) when
     * an edit's base is no longer live, and InvalidSubjectPayloadException (400) when the vocabulary
     * changed since submit in a way the payload no longer fits; either rolls the decision back.
     */
    @Transactional
    public void applyModerationOutcome(UUID revisionId, SubjectRevisionStatus outcome) {
        SubjectRevision revision = getRevision(revisionId);
        if (outcome == SubjectRevisionStatus.APPROVED) {
            approve(revision);
            return;
        }
        revision.setStatus(outcome);
        subjectRevisionRepository.save(revision);
    }

    private void approve(SubjectRevision revision) {
        Subject subject;
        ValidatedSubjectPayload validated;
        if (revision.getSubjectId() == null) {
            validated = validate(revision.getKind(), null, fromJson(revision.getPayload()));
            subject = subjectReadModelService.createSubject(revision.getKind(), revision.getDevMarker());
            revision.setSubjectId(subject.getId());
        } else {
            // Held to the end of the transaction, so no other approval of this subject can move
            // the live pointer between the staleness check and the rewrite below.
            subject = lockSubject(revision.getSubjectId());
            requireBasedOnLive(revision);
            validated = validate(revision.getKind(), revision.getSubjectId(), fromJson(revision.getPayload()));
        }
        revision.setPayload(toJson(validated.payload()));
        revision.setStatus(SubjectRevisionStatus.APPROVED);
        // Flushed first: the live pointer's composite FK needs the revision's subject_id written,
        // and Hibernate would otherwise flush the pointer's insert before this update.
        subjectRevisionRepository.saveAndFlush(revision);
        subjectReadModelService.publish(subject, revision.getId(), validated);
    }

    // For the moderator's task screen; the moderation route is the authorization check. While
    // the revision awaits a decision it is compared with what readers see now. Once decided, the
    // live revision may be this one or a later one, so it is compared with its own base instead.
    @Transactional(readOnly = true)
    public SubjectReviewResponse getForReview(UUID revisionId) {
        SubjectRevision revision = getRevision(revisionId);
        UUID subjectId = revision.getSubjectId();
        Optional<SubjectRevision> live = subjectId == null ? Optional.empty() : findLiveRevision(subjectId);
        boolean isDecided = revision.getStatus() == SubjectRevisionStatus.APPROVED
                || revision.getStatus() == SubjectRevisionStatus.REJECTED;

        SubjectPayloadRequest before;
        if (subjectId == null) {
            before = null;
        } else if (!isDecided) {
            before = live.map(liveRevision -> fromJson(liveRevision.getPayload()))
                    .orElseGet(() -> subjectReadModelService.snapshot(lockFreeSubject(subjectId)));
        } else {
            before = Optional.ofNullable(revision.getBaseRevisionId())
                    .flatMap(subjectRevisionRepository::findById)
                    .map(base -> fromJson(base.getPayload()))
                    .orElse(null);
        }
        SubjectPayloadRequest after = fromJson(revision.getPayload());

        List<SubjectFieldChangeResponse> changes = diff(before, after);
        Set<UUID> citedSourceIds = new LinkedHashSet<>();
        Map<String, List<SubjectFactValueRequest>> afterFacts = factsByKey(after);
        changes.stream()
                .filter(change -> change.field().startsWith("facts."))
                .flatMap(change -> afterFacts.getOrDefault(change.field().substring("facts.".length()), List.of())
                        .stream())
                .map(SubjectFactValueRequest::sourceId)
                .filter(Objects::nonNull)
                .forEach(citedSourceIds::add);
        List<SourceResponse> sources = sourceService.findAll(citedSourceIds);

        return new SubjectReviewResponse(toResponse(revision), live.map(SubjectRevision::getRevisionNumber).orElse(null),
                changes, sources);
    }

    private List<SubjectFieldChangeResponse> diff(@Nullable SubjectPayloadRequest before, SubjectPayloadRequest after) {
        List<SubjectFieldChangeResponse> changes = new ArrayList<>();
        LocalizedTextRequest beforeNames = before == null ? null : before.names();
        LocalizedTextRequest afterNames = after.names();
        addChange(changes, "names.fa", beforeNames == null ? null : beforeNames.fa(),
                afterNames == null ? null : afterNames.fa());
        addChange(changes, "names.en", beforeNames == null ? null : beforeNames.en(),
                afterNames == null ? null : afterNames.en());
        addChange(changes, "names.ar", beforeNames == null ? null : beforeNames.ar(),
                afterNames == null ? null : afterNames.ar());

        SubjectAliasesRequest beforeAliases = before == null ? null : before.aliases();
        SubjectAliasesRequest afterAliases = after.aliases();
        addChange(changes, "aliases.fa", beforeAliases == null ? null : nullIfEmpty(beforeAliases.fa()),
                afterAliases == null ? null : nullIfEmpty(afterAliases.fa()));
        addChange(changes, "aliases.en", beforeAliases == null ? null : nullIfEmpty(beforeAliases.en()),
                afterAliases == null ? null : nullIfEmpty(afterAliases.en()));
        addChange(changes, "aliases.ar", beforeAliases == null ? null : nullIfEmpty(beforeAliases.ar()),
                afterAliases == null ? null : nullIfEmpty(afterAliases.ar()));

        Map<String, List<SubjectFactValueRequest>> beforeFacts = before == null ? Map.of() : factsByKey(before);
        Map<String, List<SubjectFactValueRequest>> afterFacts = factsByKey(after);
        Set<String> keys = new LinkedHashSet<>(afterFacts.keySet());
        keys.addAll(beforeFacts.keySet());
        for (String key : keys) {
            addChange(changes, "facts." + key, beforeFacts.get(key), afterFacts.get(key));
        }

        addChange(changes, "geometry", before == null ? null : before.geometry(), after.geometry());
        addChange(changes, "mainImageMediaId", before == null ? null : before.mainImageMediaId(),
                after.mainImageMediaId());
        return changes;
    }

    private void addChange(List<SubjectFieldChangeResponse> changes, String field, @Nullable Object before,
            @Nullable Object after) {
        JsonNode beforeNode = before == null ? null : objectMapper.valueToTree(before);
        JsonNode afterNode = after == null ? null : objectMapper.valueToTree(after);
        if (!Objects.equals(beforeNode, afterNode)) {
            changes.add(new SubjectFieldChangeResponse(field, beforeNode, afterNode));
        }
    }

    private static Map<String, List<SubjectFactValueRequest>> factsByKey(SubjectPayloadRequest payload) {
        Map<String, List<SubjectFactValueRequest>> byKey = new LinkedHashMap<>();
        Objects.requireNonNullElse(payload.facts(), List.<SubjectFactRequest>of()).stream()
                .filter(fact -> fact != null && fact.attribute() != null)
                .forEach(fact -> byKey.putIfAbsent(fact.attribute(),
                        Objects.requireNonNullElse(fact.values(), List.of())));
        return byKey;
    }

    private static @Nullable List<String> nullIfEmpty(@Nullable List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private ValidatedSubjectPayload validate(SubjectKind kind, @Nullable UUID subjectId,
            SubjectPayloadRequest payload) {
        SubjectPayloadRequest livePayload = subjectId == null ? null
                : findLiveRevision(subjectId).map(live -> fromJson(live.getPayload())).orElse(null);
        return subjectPayloadValidationService.validate(kind, subjectId, payload, livePayload);
    }

    private void requireBasedOnLive(SubjectRevision revision) {
        Optional<SubjectRevision> live = findLiveRevision(revision.getSubjectId());
        UUID liveId = live.map(SubjectRevision::getId).orElse(null);
        if (Objects.equals(liveId, revision.getBaseRevisionId())) {
            return;
        }
        Integer baseNumber = Optional.ofNullable(revision.getBaseRevisionId())
                .flatMap(subjectRevisionRepository::findById)
                .map(SubjectRevision::getRevisionNumber)
                .orElse(null);
        throw new StaleSubjectRevisionException(baseNumber, live.map(SubjectRevision::getRevisionNumber).orElse(null));
    }

    private Optional<SubjectRevision> findLiveRevision(UUID subjectId) {
        return subjectLiveRevisionRepository.findById(subjectId)
                .map(SubjectLiveRevision::getRevisionId)
                .flatMap(subjectRevisionRepository::findById);
    }

    private Subject lockSubject(UUID subjectId) {
        return subjectRepository.findByIdForUpdate(subjectId).orElseThrow(SubjectNotFoundException::new);
    }

    private Subject lockFreeSubject(UUID subjectId) {
        return subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new);
    }

    private SubjectRevision getRevision(UUID revisionId) {
        return subjectRevisionRepository.findById(revisionId).orElseThrow(SubjectRevisionNotFoundException::new);
    }

    // Only APPROVED revisions are public; the rest are their author's and moderators' (ADMIN
    // included, resolved by the caller through the role hierarchy). Fails closed like
    // ArticleRevisionService.isReadableBy: an anonymous caller matches no author.
    private boolean isReadableBy(SubjectRevision revision, @Nullable UUID callerUserId, boolean isCallerModerator) {
        return revision.getStatus() == SubjectRevisionStatus.APPROVED
                || isCallerModerator
                || revision.getAuthorId().equals(callerUserId);
    }

    private void requireAuthor(SubjectRevision revision, UUID callerUserId) {
        if (!revision.getAuthorId().equals(callerUserId)) {
            throw new NotSubjectRevisionAuthorException();
        }
    }

    private void requireEditable(SubjectRevision revision) {
        if (revision.getStatus() != SubjectRevisionStatus.DRAFT
                && revision.getStatus() != SubjectRevisionStatus.CHANGES_REQUESTED) {
            throw new SubjectRevisionNotEditableException();
        }
    }

    private String toJson(SubjectPayloadRequest payload) {
        return objectMapper.writeValueAsString(payload);
    }

    private SubjectPayloadRequest fromJson(String payload) {
        return objectMapper.readValue(payload, SubjectPayloadRequest.class);
    }

    private SubjectRevisionResponse toResponse(SubjectRevision revision) {
        return new SubjectRevisionResponse(revision.getId(), revision.getSubjectId(), revision.getKind(),
                revision.getRevisionNumber(), revision.getBaseRevisionId(),
                objectMapper.readValue(revision.getPayload(), SubjectPayloadResponse.class), revision.getSummary(),
                revision.getStatus(), revision.getAuthorId(), revision.getCreatedAt());
    }
}

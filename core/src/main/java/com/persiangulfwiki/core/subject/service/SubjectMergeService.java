package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.media.service.MediaReadService;
import com.persiangulfwiki.core.subject.dto.SubjectMergeResponse;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectRedirect;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.exception.InvalidMergeTargetException;
import com.persiangulfwiki.core.subject.exception.SubjectDeletedException;
import com.persiangulfwiki.core.subject.exception.SubjectKindMismatchException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectAliasRepository;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Merges a duplicate subject (the source) into another of the same kind (the target), in one
// transaction holding both subject row locks. Everything the source said or had ends up on the target:
//
// - its names, aliases, facts and geometry, through a new APPROVED target revision whose payload is
//   both combined (SubjectPayloadMerger) -- through the revision, not straight into the read model,
//   since the next approval rebuilds the read model from the live payload and would drop anything
//   written around it;
// - its articles, and with them their galleries (SubjectArticleService.moveArticles keeps the target's
//   main article, or carries the source's over when the target has none);
// - every other subject's fact pointing at it.
//
// The source row stays, deleted, with a redirect to the target: its revision history and verification
// marks are kept, and anything still naming it -- article links, revision payloads, GET by id -- follows
// the redirect instead of being rewritten. A merge cannot be undone.
@Service
@RequiredArgsConstructor
public class SubjectMergeService {

    private final SubjectRepository subjectRepository;
    private final SubjectRedirectRepository subjectRedirectRepository;
    private final SubjectFactRepository subjectFactRepository;
    private final SubjectAliasRepository subjectAliasRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final SubjectRevisionService subjectRevisionService;
    private final SubjectArticleService subjectArticleService;
    private final MediaReadService mediaReadService;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final AuditLogService auditLogService;

    /**
     * @throws SubjectNotFoundException if the source does not exist
     * @throws InvalidMergeTargetException if the target does not exist, is deleted, or is the source
     * @throws SubjectDeletedException if the source is already deleted or merged away
     * @throws SubjectKindMismatchException if the two are of different kinds
     * @throws com.persiangulfwiki.core.subject.exception.SubjectMergeInvalidException if what the two say
     *         together fails the field vocabulary (thrown by SubjectRevisionService.publishMerge)
     */
    @Transactional
    public SubjectMergeResponse merge(UUID moderatorId, UUID sourceId, UUID targetId) {
        if (sourceId.equals(targetId)) {
            throw new InvalidMergeTargetException("cannot merge subject " + sourceId + " into itself",
                    "validation.subjectMerge.sameSubject");
        }
        // Locked in id order, so two merges of the same pair in opposite directions can't deadlock.
        lockInIdOrder(sourceId, targetId);
        Subject source = subjectRepository.findById(sourceId).orElseThrow(SubjectNotFoundException::new);
        Subject target = subjectRepository.findById(targetId)
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new InvalidMergeTargetException("no undeleted subject " + targetId));
        if (source.isDeleted()) {
            throw new SubjectDeletedException();
        }
        if (source.getKind() != target.getKind()) {
            throw new SubjectKindMismatchException();
        }
        SubjectPayloadRequest sourcePayload = subjectRevisionService.currentPayload(source);
        SubjectPayloadRequest targetPayload = subjectRevisionService.currentPayload(target);

        // Clears the persistence context, so both subjects are read again below.
        subjectArticleService.moveArticles(sourceId, targetId);
        source = subjectRepository.findById(sourceId).orElseThrow(SubjectNotFoundException::new);
        target = subjectRepository.findById(targetId).orElseThrow(SubjectNotFoundException::new);

        // Before the target's revision is validated, so the validator rewrites the source's id to the
        // target's in the merged payload and drops what would become a reference to itself.
        subjectRedirectRepository.repointTargets(sourceId, targetId);
        SubjectRedirect redirect = subjectRedirectRepository.save(SubjectRedirect.builder()
                .sourceId(sourceId)
                .targetId(targetId)
                .build());
        source.setDeletedAt(Instant.now());
        subjectRepository.save(source);

        Set<String> multiValuedKeys = attributeDefinitionRepository.findByKind(target.getKind()).stream()
                .filter(AttributeDefinition::isMulti)
                .map(AttributeDefinition::getKey)
                .collect(Collectors.toSet());
        SubjectPayloadRequest merged = SubjectPayloadMerger.merge(targetPayload, sourcePayload, multiValuedKeys,
                mainImage(targetId, targetPayload, sourcePayload));
        SubjectRevision revision = subjectRevisionService.publishMerge(moderatorId, target, merged);

        // The source's read model now lives on the target. Its labels stay, so its history still reads.
        subjectFactRepository.deleteBySubjectId(sourceId);
        subjectAliasRepository.deleteBySubjectId(sourceId);
        // Other subjects' published references follow the merge; their payloads are rewritten only when
        // next copied or validated.
        subjectFactRepository.repointReferences(sourceId, targetId, targetId);
        subjectFactRepository.removeDuplicateReferences(targetId);

        auditLogService.record(moderatorId, "SUBJECT_MERGE", null, "sourceId=" + sourceId + ", targetId=" + targetId
                + ", kind=" + target.getKind() + ", revisionId=" + revision.getId());
        subjectInfoboxCacheService.evictAll();
        return new SubjectMergeResponse(sourceId, targetId, redirect.getMergedAt(), revision.getId(),
                revision.getRevisionNumber());
    }

    private void lockInIdOrder(UUID first, UUID second) {
        Stream.of(first, second)
                .sorted(Comparator.naturalOrder())
                .forEach(subjectRepository::findByIdForUpdate);
    }

    // The target's pick, else the source's, as long as it is still a public image of one of the target's
    // articles -- which now include the source's. One that stopped being public is dropped rather than
    // failing the merge: the read side drops it anyway.
    private @Nullable UUID mainImage(UUID targetId, SubjectPayloadRequest targetPayload,
            SubjectPayloadRequest sourcePayload) {
        return Stream.of(targetPayload.mainImageMediaId(), sourcePayload.mainImageMediaId())
                .filter(Objects::nonNull)
                .filter(mediaId -> mediaReadService.isPublishedImageOfSubject(mediaId, targetId))
                .findFirst()
                .orElse(null);
    }
}

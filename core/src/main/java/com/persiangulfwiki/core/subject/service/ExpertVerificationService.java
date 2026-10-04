package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.ExpertVerificationResponse;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadResponse;
import com.persiangulfwiki.core.subject.dto.SubjectVerificationResponse;
import com.persiangulfwiki.core.subject.dto.VerificationQueueItemResponse;
import com.persiangulfwiki.core.subject.dto.VerificationState;
import com.persiangulfwiki.core.subject.entity.ExpertVerification;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.exception.NotSubjectKindExpertException;
import com.persiangulfwiki.core.subject.exception.SelfVerificationNotAllowedException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionAlreadyVerifiedException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotLiveException;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository.LatestVerifiedRevision;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

// Expert reviewers' marks on published subject revisions: advisory, after publishing, and never
// taken back. A mark belongs to the revision it was made on, so a subject's state is worked out
// against whatever is live now -- VERIFIED, STALE or NONE (see VerificationState).
//
// Expertise is read from user_roles rather than the access token. The token names roles only, not
// their expertise, and reading the row also refuses a role revoked since the token was issued. A
// role with no expertise matches no kind: it fails closed rather than meaning "every kind".
//
// An expert may not verify a revision they wrote: the mark tells readers someone *else* checked
// it. Moderators, by contrast, may decide their own revisions (see ModerationService) -- that is
// a policy check, not a second opinion.
@Service
@RequiredArgsConstructor
public class ExpertVerificationService {

    private final ExpertVerificationRepository expertVerificationRepository;
    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final UserRoleRepository userRoleRepository;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final ObjectMapper objectMapper;

    /**
     * Checked in this order: a revision the caller may not read is a 404, as on
     * GET /api/subject-revisions/{id}; then the caller's expertise and authorship (403); then
     * whether the revision is live and not yet marked by this caller (409).
     */
    @Transactional
    public ExpertVerificationResponse verify(UUID expertId, UUID revisionId, boolean isCallerModerator) {
        SubjectRevision revision = subjectRevisionRepository.findById(revisionId)
                .filter(candidate -> SubjectRevisionService.isReadableBy(candidate, expertId, isCallerModerator))
                .orElseThrow(SubjectRevisionNotFoundException::new);
        if (!expertiseOf(expertId).contains(revision.getKind())) {
            throw new NotSubjectKindExpertException();
        }
        if (revision.getAuthorId().equals(expertId)) {
            throw new SelfVerificationNotAllowedException();
        }
        // A proposal that was never approved has no subject, so nothing of it is live.
        UUID subjectId = revision.getSubjectId();
        if (subjectId == null) {
            throw new SubjectRevisionNotLiveException();
        }
        // The lock approval holds while it moves the live pointer, taken before the live check and
        // held to commit, so a mark cannot land on a revision that stopped being live in between.
        // It also serializes two identical requests, so the duplicate check below cannot race.
        subjectRepository.findByIdForUpdate(subjectId).orElseThrow(SubjectNotFoundException::new);
        boolean isLive = subjectLiveRevisionRepository.findById(subjectId)
                .map(live -> live.getRevisionId().equals(revisionId))
                .orElse(false);
        if (!isLive) {
            throw new SubjectRevisionNotLiveException();
        }
        if (expertVerificationRepository.existsBySubjectRevisionIdAndExpertId(revisionId, expertId)) {
            throw new SubjectRevisionAlreadyVerifiedException();
        }

        ExpertVerification verification = expertVerificationRepository.save(ExpertVerification.builder()
                .subjectRevisionId(revisionId)
                .expertId(expertId)
                .build());
        // The infobox shows the verification state.
        subjectInfoboxCacheService.evictAll();
        return new ExpertVerificationResponse(verification.getId(), revisionId, subjectId,
                revision.getRevisionNumber(), expertId, verification.getVerifiedAt());
    }

    /**
     * Live revisions in the caller's expertise that no expert has marked yet, newest approval
     * first, leaving out the caller's own. With {@code kindFilter}, only that kind -- a 403 when it
     * isn't one of the caller's. Also a 403 when the caller has no expertise at all.
     */
    @Transactional(readOnly = true)
    public List<VerificationQueueItemResponse> queue(UUID expertId, @Nullable String kindFilter, Pageable pageable) {
        Set<SubjectKind> expertise = expertiseOf(expertId);
        Set<SubjectKind> kinds = kindFilter == null ? expertise : Set.of(SubjectService.parseKind(kindFilter));
        if (kinds.isEmpty() || !expertise.containsAll(kinds)) {
            throw new NotSubjectKindExpertException();
        }

        List<SubjectLiveRevision> liveRevisions = subjectLiveRevisionRepository.findUnverified(kinds, expertId, pageable);
        if (liveRevisions.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubjectRevision> revisionsById = subjectRevisionRepository
                .findAllById(liveRevisions.stream().map(SubjectLiveRevision::getRevisionId).toList()).stream()
                .collect(Collectors.toMap(SubjectRevision::getId, Function.identity()));
        Map<UUID, Integer> latestVerifiedBySubject = expertVerificationRepository
                .findLatestVerifiedRevisions(liveRevisions.stream().map(SubjectLiveRevision::getSubjectId).toList())
                .stream()
                .collect(Collectors.toMap(LatestVerifiedRevision::getSubjectId,
                        LatestVerifiedRevision::getRevisionNumber));

        return liveRevisions.stream()
                .map(live -> {
                    SubjectRevision revision = Objects.requireNonNull(revisionsById.get(live.getRevisionId()));
                    SubjectPayloadResponse payload =
                            objectMapper.readValue(revision.getPayload(), SubjectPayloadResponse.class);
                    return new VerificationQueueItemResponse(live.getSubjectId(), revision.getKind(), payload.names(),
                            revision.getId(), revision.getRevisionNumber(), live.getApprovedAt(),
                            verificationOf(revision.getRevisionNumber(), latestVerifiedBySubject.get(live.getSubjectId())));
                })
                .toList();
    }

    /** The verification state of the subject whose live revision is {@code liveRevisionNumber}. */
    @Transactional(readOnly = true)
    public SubjectVerificationResponse verificationOf(UUID subjectId, int liveRevisionNumber) {
        Integer latestVerified = expertVerificationRepository.findLatestVerifiedRevisions(List.of(subjectId)).stream()
                .findFirst()
                .map(LatestVerifiedRevision::getRevisionNumber)
                .orElse(null);
        return verificationOf(liveRevisionNumber, latestVerified);
    }

    private Set<SubjectKind> expertiseOf(UUID userId) {
        Set<SubjectKind> kinds = EnumSet.noneOf(SubjectKind.class);
        userRoleRepository.findByUserId(userId).stream()
                .filter(role -> role.getRole() == Role.EXPERT_REVIEWER)
                .map(UserRole::getExpertise)
                .filter(Objects::nonNull)
                .forEach(kinds::add);
        return kinds;
    }

    // Only the live revision or an older one can carry a mark -- verify() refuses anything else,
    // and the live revision is always the newest approved -- so the newest marked revision is
    // either the live one or behind it.
    private static SubjectVerificationResponse verificationOf(int liveRevisionNumber,
            @Nullable Integer latestVerifiedRevisionNumber) {
        if (latestVerifiedRevisionNumber == null) {
            return new SubjectVerificationResponse(VerificationState.NONE, null);
        }
        VerificationState state = latestVerifiedRevisionNumber == liveRevisionNumber
                ? VerificationState.VERIFIED
                : VerificationState.STALE;
        return new SubjectVerificationResponse(state, latestVerifiedRevisionNumber);
    }
}

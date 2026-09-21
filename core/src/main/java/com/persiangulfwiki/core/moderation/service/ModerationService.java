package com.persiangulfwiki.core.moderation.service;

import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.event.RevisionSubmittedEvent;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.dto.ModerationDecisionResponse;
import com.persiangulfwiki.core.moderation.dto.ModerationTaskResponse;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationDecision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.exception.InvalidModerationTaskStateException;
import com.persiangulfwiki.core.moderation.exception.MissingDecisionReasonException;
import com.persiangulfwiki.core.moderation.exception.ModerationTaskNotFoundException;
import com.persiangulfwiki.core.moderation.exception.NotTaskClaimantException;
import com.persiangulfwiki.core.moderation.exception.RevisionNotPendingException;
import com.persiangulfwiki.core.moderation.exception.TaskAlreadyDecidedException;
import com.persiangulfwiki.core.moderation.exception.TaskNotClaimableException;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The editorial gate between "an author says this is ready" and "readers can see it".
//
// A moderator judges policy, not facts -- is this in scope, sourced, and allowed on the wiki.
// Whether the content is actually *correct* is Phase 5's ExpertReview, which is advisory and
// can never change anything this class writes.
//
// Three decisions, and the asymmetry between them is the whole design:
//   APPROVE         -> revision APPROVED, task DECIDED, revision becomes the translation's
//                      published content.
//   REJECT          -> revision REJECTED, task DECIDED. Terminal. Another attempt means a
//                      brand new revision, which gets a brand new task.
//   REQUEST_CHANGES -> revision CHANGES_REQUESTED, task back to OPEN rather than DECIDED, and
//                      the claim released. The author edits the same revision and resubmits,
//                      and this same task is decided again. This is the only path that puts
//                      more than one decision row on one task, and it is why
//                      ModerationDecision is a table rather than three columns on the task.
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationService {

    private static final Sort QUEUE_ORDER = Sort.by(Sort.Direction.ASC, "createdAt");

    private final ModerationTaskRepository moderationTaskRepository;
    private final ModerationDecisionRepository moderationDecisionRepository;
    private final ArticleRevisionService articleRevisionService;

    // The article package's half of the submit endpoint, joined here rather than by a direct
    // call in the other direction -- see RevisionSubmittedEvent for why this is an event at
    // all, and why it must stay a plain @EventListener (synchronous, inside the publisher's
    // transaction) rather than becoming an @TransactionalEventListener.
    //
    // Both branches converge on a task in OPEN with no claim, because both mean the same
    // thing to a moderator: this revision is waiting for someone to pick it up. What differs
    // is only whether a task already exists.
    @EventListener
    @Transactional
    public void onRevisionSubmitted(RevisionSubmittedEvent event) {
        Optional<ModerationTask> existing = moderationTaskRepository.findByRevisionId(event.revisionId());

        // The event's account of which branch this is and what the table actually holds must
        // agree: a first submission has no task yet, a resubmission does. A mismatch means one
        // of the two has drifted -- the realistic cause being a task minted straight into the
        // table by the dev fixture endpoint, against a revision the real flow never moved. It
        // is worth surfacing rather than silently resolving, but not worth failing an author's
        // submit over, since either branch below still leaves the task in the one state a
        // moderator needs to see it in.
        if (existing.isPresent() == event.firstSubmission()) {
            log.warn("revision {} submitted as firstSubmission={} but {} an existing moderation task",
                    event.revisionId(), event.firstSubmission(), existing.isPresent() ? "has" : "has no");
        }

        existing.ifPresentOrElse(this::reopen, () -> open(event.revisionId()));
    }

    private void open(UUID revisionId) {
        moderationTaskRepository.save(ModerationTask.builder()
                .revisionId(revisionId)
                .state(ModerationTaskState.OPEN)
                .build());
        log.debug("opened moderation task for revision {}", revisionId);
    }

    // A resubmission after REQUEST_CHANGES. The task is already OPEN and already unclaimed
    // (decide released it when it sent the revision back), so this is normally a no-op write
    // -- it is written unconditionally anyway so that the invariant "a task awaiting a
    // moderator is OPEN and unclaimed" is restored by the submit path itself rather than
    // being merely inherited from whatever the decide path last left behind.
    private void reopen(ModerationTask task) {
        task.setState(ModerationTaskState.OPEN);
        releaseClaim(task);
        moderationTaskRepository.save(task);
        log.debug("reopened moderation task {} for revision {}", task.getId(), task.getRevisionId());
    }

    @Transactional
    public ModerationTaskResponse claim(UUID moderatorId, UUID taskId) {
        ModerationTask task = getTask(taskId);

        // Read as: claimable unless it is finished, or somebody already holds it. Phrased on
        // claimedBy rather than on state == OPEN, which is not merely a restatement -- the two
        // differ in exactly one case, and it is a case that occurs. claimed_by is ON DELETE
        // SET NULL (see V17), so deleting a moderator mid-claim leaves a task in CLAIMED with
        // nobody holding it; a state == OPEN test would refuse to claim that task while
        // decide() refused it to everyone for not being the claimant, stranding it
        // permanently. This phrasing hands it back to the queue instead.
        //
        // Re-claiming by the moderator who already holds it is still refused, so a claim is
        // never silently extended.
        if (task.getState() == ModerationTaskState.DECIDED || task.getClaimedBy() != null) {
            throw new TaskNotClaimableException();
        }

        task.setState(ModerationTaskState.CLAIMED);
        task.setClaimedBy(moderatorId);
        task.setClaimedAt(Instant.now());

        return toResponse(moderationTaskRepository.save(task));
    }

    // Guard order here is deliberate and the reason is worth stating, because reordering it
    // silently changes which status a client sees for an overlapping failure:
    //   1. DECIDED  -> 409. Terminal, and true regardless of who is asking.
    //   2. claimant -> 403. Covers an OPEN task too: nobody holds it, so nobody may decide
    //                  it, and "claim it first" is an authorization answer rather than a
    //                  state-machine one. This also means TaskNotClaimableException belongs
    //                  to claim() alone and can never compete with this.
    //   3. PENDING  -> 409. Last, because it is about the revision rather than the task.
    //
    // That third guard is what closes the window REQUEST_CHANGES opens. Sending a revision
    // back leaves the task OPEN while the revision sits at CHANGES_REQUESTED, so the task is
    // claimable again immediately -- but the author has not fixed anything yet, and deciding
    // on unchanged content would be meaningless. Requiring PENDING means a moderator may pick
    // the task back up at any time but cannot rule on it until the author actually
    // resubmits.
    @Transactional
    public ModerationTaskResponse decide(UUID moderatorId, UUID taskId, DecideRequest request) {
        ModerationTask task = getTask(taskId);

        if (task.getState() == ModerationTaskState.DECIDED) {
            throw new TaskAlreadyDecidedException();
        }
        if (!moderatorId.equals(task.getClaimedBy())) {
            throw new NotTaskClaimantException();
        }
        if (articleRevisionService.getStatus(task.getRevisionId()) != RevisionStatus.PENDING) {
            throw new RevisionNotPendingException();
        }
        requireReasonWhenSendingBack(request);

        moderationDecisionRepository.save(ModerationDecision.builder()
                .taskId(task.getId())
                .moderatorId(moderatorId)
                .decision(request.decision())
                .reason(request.reason())
                .build());

        // The revision's new status and the task's new state are two different things and
        // are decided separately -- REQUEST_CHANGES is precisely the case where they diverge
        // (revision moves on, task goes back in the queue).
        articleRevisionService.applyModerationOutcome(task.getRevisionId(), outcomeFor(request.decision()));

        if (request.decision() == Decision.REQUEST_CHANGES) {
            task.setState(ModerationTaskState.OPEN);
            releaseClaim(task);
        } else {
            task.setState(ModerationTaskState.DECIDED);
        }

        log.info("moderation task {} decided {} by moderator {}", task.getId(), request.decision(), moderatorId);
        return toResponse(moderationTaskRepository.save(task));
    }

    // stateFilter is the raw query-parameter string, not a bound enum -- see DecideRequest
    // for why the body and the filter are treated differently. Null means "every state".
    @Transactional(readOnly = true)
    public List<ModerationTaskResponse> list(String stateFilter, Pageable pageable) {
        Pageable ordered = withQueueOrder(pageable);
        List<ModerationTask> tasks = stateFilter == null
                ? moderationTaskRepository.findAll(ordered).getContent()
                : moderationTaskRepository.findByState(parseState(stateFilter), ordered).getContent();
        return tasks.stream().map(this::toResponse).toList();
    }

    // Paging without an explicit sort leaves row order up to the database, which is free to
    // return a different order per page -- so a moderator walking the queue could see the same
    // task twice and never see another at all. Oldest-first is also the order the queue should
    // be worked in: the task that has been waiting longest is the one to pick up next.
    private Pageable withQueueOrder(Pageable pageable) {
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), QUEUE_ORDER);
    }

    // Only APPROVE has nothing for the author to act on. The other two send the revision back
    // to a human who has to understand why, and a bare "rejected" with no note is a dead end
    // for them -- so an empty reason is refused rather than stored.
    private void requireReasonWhenSendingBack(DecideRequest request) {
        if (request.decision() == Decision.APPROVE) {
            return;
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new MissingDecisionReasonException();
        }
    }

    private RevisionStatus outcomeFor(Decision decision) {
        return switch (decision) {
            case APPROVE -> RevisionStatus.APPROVED;
            case REJECT -> RevisionStatus.REJECTED;
            case REQUEST_CHANGES -> RevisionStatus.CHANGES_REQUESTED;
        };
    }

    // Cleared as a pair, always -- ck_moderation_tasks_claim_pair (V17) rejects a row with
    // one of the two set and the other null.
    private void releaseClaim(ModerationTask task) {
        task.setClaimedBy(null);
        task.setClaimedAt(null);
    }

    private ModerationTaskState parseState(String stateFilter) {
        try {
            return ModerationTaskState.valueOf(stateFilter.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidModerationTaskStateException();
        }
    }

    private ModerationTask getTask(UUID taskId) {
        return moderationTaskRepository.findById(taskId).orElseThrow(ModerationTaskNotFoundException::new);
    }

    private ModerationTaskResponse toResponse(ModerationTask task) {
        List<ModerationDecisionResponse> decisions =
                moderationDecisionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                        .map(decision -> new ModerationDecisionResponse(decision.getId(), decision.getTaskId(),
                                decision.getModeratorId(), decision.getDecision(), decision.getReason(),
                                decision.getCreatedAt()))
                        .toList();
        return new ModerationTaskResponse(task.getId(), task.getRevisionId(), task.getState(), task.getClaimedBy(),
                task.getClaimedAt(), task.getCreatedAt(), task.getUpdatedAt(), decisions);
    }
}

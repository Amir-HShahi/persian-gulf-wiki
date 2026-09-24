package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.exception.RevisionNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.dev.dto.DevTestModerationRequest;
import com.persiangulfwiki.core.dev.dto.DevTestModerationResponse;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationDecision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.exception.ModerationTaskNotFoundException;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

// Mints a throwaway moderation task -- optionally already claimed, already decided, and with a
// decision already on its history -- for Phase 5 / E2E suites that need a task at a specific
// point in its life without first driving a whole submit -> claim -> decide sequence.
//
// Two independent gates keep this out of production, exactly as for DevTestArticleController:
// @Profile("dev") here, so the bean does not exist, and DevSecurityConfig's profiled filter
// chain, so /api/dev/** is not permitted anywhere else. Both are needed, and neither needed a
// change for this endpoint -- that chain matches the whole /api/dev/** prefix, so a new route
// under it is covered the moment it is mapped.
//
// Deliberately bypasses ModerationService. Every state past OPEN is gated there behind a real
// moderator: claim refuses anything not OPEN, and decide refuses anyone who is not the account
// currently holding the claim *and* refuses a revision that is not PENDING. So reaching
// "a CLAIMED task held by a known account" or "a DECIDED task with one decision on it" through
// the API costs an authenticated MODERATOR session plus two round trips per fixture, and
// "a DECIDED task whose revision was never PENDING" is unreachable at any price. Writing the
// rows directly through the repositories, with state taken as-is from the request, is what
// makes those states available to a suite in one call.
@RestController
@RequestMapping("/api/dev/test-moderation-tasks")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Moderation Tasks", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestModerationController {

    // DevUserSeeder's stable moderator fixture. Deliberately a seeded account rather than a
    // freshly minted one -- see resolveModeratorUserId for the sweep-ordering race that choice
    // avoids.
    private static final String SEEDED_MODERATOR_EMAIL = "moderator@dev.local";

    // Enough of the UUID to keep parallel runs from producing identical usernames in a failing
    // test's output, short enough to stay readable there.
    private static final int SLUG_LENGTH = 12;

    private final ModerationTaskRepository moderationTaskRepository;
    private final ModerationDecisionRepository moderationDecisionRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final UserRepository userRepository;

    @Operation(summary = "Mint a disposable test moderation task", description = "Creates a moderation task marked as machine-minted, pointing at an existing "
            + "revision. Every field of the request body is optional except the revision id, "
            + "which must name a revision that exists. state defaults to open but accepts "
            + "claimed or decided directly, and a decision can be seeded onto the task's "
            + "history in the same call -- states the normal claim/decide endpoints can only "
            + "reach through a full review round performed by a real moderator account.")
    @ApiResponse(responseCode = "201", description = "The created task, and the id of the seeded decision if one was requested.")
    @ApiResponse(responseCode = "404", description = "The revision id was omitted, or does not name an existing revision.")
    @ApiResponse(responseCode = "409", description = "A task already exists for that revision. At most one task can ever exist per "
            + "revision, so a second one has to be a new revision instead.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Returns the DTO bare rather than wrapped in the usual envelope, matching
    // DevTestArticleController: there is no message worth translating for a machine caller.
    public DevTestModerationResponse mint(@RequestBody(required = false) DevTestModerationRequest request) {
        DevTestModerationRequest safeRequest =
                request != null ? request : new DevTestModerationRequest(null, null, null, null, null);

        // Checked up front rather than left to the foreign key, which would surface as a 500.
        // A null id is folded into the same 404 for the same reason: "no such revision" is the
        // honest answer either way, and the alternative is a new exception class plus a
        // GlobalExceptionHandler entry plus three translated messages for a machine caller.
        UUID revisionId = safeRequest.revisionId();
        if (revisionId == null || !articleRevisionRepository.existsById(revisionId)) {
            throw new RevisionNotFoundException();
        }

        ModerationTaskState state = safeRequest.state() != null ? safeRequest.state() : ModerationTaskState.OPEN;
        UUID claimantUserId = resolveClaimantUserId(state, safeRequest.claimedByUserId());

        ModerationTask task = moderationTaskRepository.save(ModerationTask.builder()
                .revisionId(revisionId)
                .state(state)
                // Written as a pair, always: ck_moderation_tasks_claim_pair (V17) rejects a
                // row with one of the two set and the other null, and it is enforced in the
                // database precisely so that this endpoint, which skips the service that
                // normally maintains the pair, cannot get it wrong quietly.
                .claimedBy(claimantUserId)
                .claimedAt(claimantUserId != null ? Instant.now() : null)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        UUID decisionId = safeRequest.decision() != null
                ? seedDecision(task.getId(), claimantUserId, safeRequest.decision(), safeRequest.reason())
                : null;

        return new DevTestModerationResponse(task.getId(), revisionId, state, claimantUserId, decisionId);
    }

    @Operation(summary = "Delete a minted test moderation task", description = "Deletes a task previously created by this endpoint. Refuses anything else -- a "
            + "task opened by the normal submit flow is reported as 404 rather than deleted. "
            + "Idempotent: deleting an id twice returns 404 the second time. Any decisions on "
            + "the task's history are removed along with it.")
    @ApiResponse(responseCode = "204", description = "The task was deleted.")
    @ApiResponse(responseCode = "404", description = "No such task, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID taskId) {
        // Deliberately narrower than "delete whatever id I am given" -- see
        // DevTestSubjectController.delete for why the marker check is load-bearing. It matters
        // more here than for the other fixture tables: a moderation task is the only record
        // that an editorial judgement was ever asked for, so a teardown hook that deleted an
        // arbitrary id would erase audit history rather than merely inconveniencing someone.
        ModerationTask task = moderationTaskRepository.findById(taskId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                // Reuses the production exception rather than a dev-only one:
                // GlobalExceptionHandler already maps it to 404.
                .orElseThrow(ModerationTaskNotFoundException::new);

        // moderation_decisions.task_id is ON DELETE CASCADE (V17), so the seeded decision rows
        // go with it without an explicit cleanup pass here.
        moderationTaskRepository.delete(task);
    }

    // Which states carry a claim, and why each answer differs:
    //
    //   OPEN     -- never. V17's claimed_by comment makes this the model rather than a
    //               convention: a task in the queue has no holder, and reopen() clears the
    //               pair for exactly that reason. A caller-supplied claimant is ignored here
    //               rather than honored, because the resulting row would be one no code path
    //               downstream knows how to read.
    //   CLAIMED  -- always. A CLAIMED task with no claimant is not merely odd, it is useless
    //               as a fixture: ModerationService.decide refuses anyone who is not the
    //               claimant, so no caller could ever decide it. So an omitted claimant is
    //               defaulted rather than left null.
    //   DECIDED  -- whatever the caller named, and nothing if they named nobody. The real
    //               decide path leaves the claim in place on APPROVE/REJECT, so a claimant is
    //               plausible here, but nothing reads it once the task is terminal, so there
    //               is no reason to invent one.
    private UUID resolveClaimantUserId(ModerationTaskState state, UUID requestedClaimantUserId) {
        return switch (state) {
            case OPEN -> null;
            case CLAIMED -> resolveModeratorUserId(requestedClaimantUserId);
            case DECIDED -> requestedClaimantUserId;
        };
    }

    // The decision is attributed to whoever holds the task when one is present, since that is
    // what the real path records (ModerationDecision.moderatorId is the claimant at the moment
    // of the decision). A seeded decision on an OPEN task -- which is what a REQUEST_CHANGES
    // round looks like after the claim is released -- has no claimant to inherit, so it falls
    // back to the same seeded account.
    private UUID seedDecision(UUID taskId, UUID claimantUserId, Decision decision, String reason) {
        ModerationDecision seeded = moderationDecisionRepository.save(ModerationDecision.builder()
                .taskId(taskId)
                .moderatorId(resolveModeratorUserId(claimantUserId))
                .decision(decision)
                .reason(reason)
                .build());
        return seeded.getId();
    }

    // moderation_decisions.moderator_id is NOT NULL with no ON DELETE action (V17) -- inserting
    // an arbitrary or fabricated UUID here would fail the foreign key at insert time, not
    // silently produce a dangling reference.
    //
    // So an omitted moderator falls back to DevUserSeeder's moderator@dev.local, and that
    // choice is load-bearing rather than arbitrary, for exactly the reason spelled out in
    // DevTestArticleController.resolveAuthorUserId: a seeded account carries no e2e- prefix, so
    // DevTestUserSweeper can never reclaim it. Minting a fresh disposable user here instead
    // would be the obvious move and is the wrong one -- that user and this task are created in
    // the same instant with the same TTL, but DevTestModerationSweeper runs at :10 and
    // DevTestUserSweeper at :30, so the user sweep's threshold is twenty minutes more
    // permissive than the task sweep's. A pair created inside that twenty-minute band has its
    // user swept while its task survives, and then:
    //   - moderator_id's RESTRICT aborts the *entire* user sweep pass, not just the blocked
    //     row, if a decision was seeded; and
    //   - claimed_by's ON DELETE SET NULL, which looks like the safe half of V17, aborts it
    //     just as hard if the task is CLAIMED: nulling claimed_by while claimed_at stays set
    //     is precisely the row ck_moderation_tasks_claim_pair exists to reject, so the cascade
    //     fails the check constraint mid-delete.
    // Pointing both the claimant and the moderator at a never-swept account removes that race
    // rather than timing around it.
    //
    // The disposable-mint path below survives only for the case where the seeded account is
    // genuinely absent (someone deleted it by hand), so this endpoint still works rather than
    // failing on a missing fixture.
    private UUID resolveModeratorUserId(UUID requestedModeratorUserId) {
        if (requestedModeratorUserId != null) {
            return requestedModeratorUserId;
        }
        return userRepository.findByEmail(SEEDED_MODERATOR_EMAIL)
                .map(User::getId)
                .orElseGet(this::mintDisposableModerator);
    }

    // No MODERATOR role is granted: nothing on this path goes through @PreAuthorize, and a
    // fixture that quietly handed out the role that gates every /api/moderation route would be
    // a privilege the suite did not ask for.
    private UUID mintDisposableModerator() {
        String slug = UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        User moderator = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .enabled(true)
                .emailVerified(true)
                .build());
        return moderator.getId();
    }
}

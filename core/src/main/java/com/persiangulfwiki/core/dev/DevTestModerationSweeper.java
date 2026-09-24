package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestModerationController's DELETE route -- see DevTestSubjectSweeper for the
// general reasoning, which is identical.
//
// Scheduling is enabled globally by OAuth2CleanupConfig, so this needs no @EnableScheduling of
// its own -- only the @Profile("dev") gate that keeps the bean from existing elsewhere.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestModerationSweeper {

    private final ModerationTaskRepository moderationTaskRepository;

    @Value("${app.dev.test-moderation-ttl-hours}")
    private final long testModerationTtlHours;

    // :10, and the ordering analysis that picks it is genuinely different from
    // DevTestArticleSweeper's -- do not read that comment across to this one.
    //
    // Downward (this sweeper blocking others): it cannot. Every sweeper below runs a bulk
    // DELETE, and what aborts such a pass is a RESTRICT pointing *at* a row it is trying to
    // remove. moderation_tasks holds no such reference to anything:
    //   - revision_id is ON DELETE CASCADE (V17, and its column comment says the article
    //     sweeper is one of the two reasons it is), so an expired article at :20 takes its
    //     tasks with it rather than being blocked by them. This is the opposite of the
    //     articles -> subjects relationship, where subject_id's RESTRICT is what forces
    //     DevTestArticleSweeper to run first.
    //   - claimed_by is ON DELETE SET NULL, so a claimed task does not block a user deletion
    //     either.
    // So a moderation task never has to be out of the way before another sweep runs, and the
    // ordering hazard DevTestArticleSweeper spends its comment on simply does not exist in
    // this direction.
    //
    // Upward (others blocking this): also nothing. Only moderation_decisions references a
    // task, via ON DELETE CASCADE, so the bulk delete below always succeeds.
    //
    // What :10 actually buys is the third direction, which is the one that bites. A task's
    // *dependents* can block DevTestUserSweeper at :30:
    //   - a seeded decision's moderator_id is NOT NULL with RESTRICT, so deleting that
    //     moderator fails outright; and
    //   - a CLAIMED task's claimed_by looks safe at ON DELETE SET NULL but is not: nulling
    //     claimed_by while claimed_at stays set is exactly the row
    //     ck_moderation_tasks_claim_pair rejects, so the cascade fails the check constraint.
    // Either one aborts the entire user sweep pass. Running here first clears expired tasks
    // (and, by cascade, their decisions) before :30 sees them, which covers the common case.
    // It is not a complete guarantee, and the gap is the same shape as the article sweeper's:
    // this threshold at :10 is twenty minutes stricter than the user sweep's, so a task minted
    // inside that trailing band survives :10 while a user of the same age is swept at :30. The
    // controller closes that gap rather than timing around it -- both the claimant and the
    // decision's moderator default to a never-swept seeded account, so only a caller that
    // explicitly names a dev-minted user can still hit it. See resolveModeratorUserId there.
    //
    // @Transactional is on *this* method, not only on deleteOlderThan, and that is
    // load-bearing: the call below is a self-invocation, which does not pass back through the
    // proxy, so deleteOlderThan's own annotation is inert on this path.
    @Scheduled(cron = "0 10 * * * *")
    @Transactional
    public void sweepExpiredTestModerationTasks() {
        deleteOlderThan(Instant.now().minus(testModerationTtlHours, ChronoUnit.HOURS));
    }

    // Split out from the scheduled trigger so the TTL boundary is testable by passing a
    // threshold, rather than by fabricating a createdAt -- which JPA cannot do anyway, since
    // AuditableEntity maps created_at as updatable = false and stamps it in @PrePersist.
    @Transactional
    public long deleteOlderThan(Instant threshold) {
        // Matches on the dev marker, so a task the real submit flow opened is untouchable by
        // this job no matter how old it gets -- its marker is null. Its decision rows cascade
        // with the task (ON DELETE CASCADE, see V17), so no separate repository call is needed
        // for them.
        long deleted =
                moderationTaskRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test moderation task(s) older than the {}h TTL", deleted, testModerationTtlHours);
        }
        return deleted;
    }
}

package com.persiangulfwiki.core.moderation.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// One thing awaiting a policy judgement, and the moderator queue's unit of work. The thing is
// either an article revision or a media metadata version -- exactly one of revisionId and
// mediaMetadataVersionId is set (ck_moderation_tasks_exactly_one_target, V18).
//
// At most one task ever exists per target (uq_moderation_tasks_revision, V17, and
// uq_moderation_tasks_media_metadata_version, V18). A rejected revision does not get a second
// task -- the author writes a new revision, and that new revision gets its own; the same holds
// for a rejected metadata version. This is why ModerationService's submit path is an
// open-or-reopen on a single findByRevisionId rather than anything that has to reason about
// which of several tasks is the live one.
//
// An AuditableEntity, unlike ArticleRevision/ModerationDecision: a task's state genuinely
// changes over its life (OPEN -> CLAIMED -> DECIDED, and back to OPEN on REQUEST_CHANGES),
// so updated_at carries real information here.
@Entity
@Table(name = "moderation_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ModerationTask extends AuditableEntity {

    @Column(name = "revision_id", updatable = false)
    private @Nullable UUID revisionId;

    @Column(name = "media_metadata_version_id", updatable = false)
    private @Nullable UUID mediaMetadataVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModerationTaskState state;

    // Set together with claimedAt on claim, cleared together with it whenever the task
    // returns to the queue -- ck_moderation_tasks_claim_pair (V17) rejects any row where
    // only one of the two is set, so the pair must always be written as a pair.
    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    // Non-null only on rows minted by DevTestModerationController; see DevTestFixtures.
    @Column(name = "dev_marker", length = 40)
    private String devMarker;
}

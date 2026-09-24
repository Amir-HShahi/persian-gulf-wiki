package com.persiangulfwiki.core.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// One round of judgement on a task: who decided, what they decided, and why. Genuinely
// immutable once written -- a later round appends another row rather than editing this one,
// which is why this is not an AuditableEntity (no updated_at to maintain) and why the class
// carries no @Setter at all, unlike ModerationTask.
//
// A task with more than one of these is a task that went through REQUEST_CHANGES at least
// once; see Decision.
@Entity
@Table(name = "moderation_decisions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ModerationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    // The moderator who held the claim when this was decided -- not necessarily the one who
    // holds the task now, since a REQUEST_CHANGES round releases the claim and the next round
    // may be picked up by someone else. See the moderator_id column comment in V17 for why
    // this is NOT NULL rather than nulled on account deletion.
    @Column(name = "moderator_id", nullable = false, updatable = false)
    private UUID moderatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Decision decision;

    // The moderator's note to the author. Required by ModerationService for REJECT and
    // REQUEST_CHANGES (the author needs to know what to fix), optional for APPROVE. Not
    // enforced by a database CHECK -- see the reason column comment in V17 for why that rule
    // lives in the service instead.
    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

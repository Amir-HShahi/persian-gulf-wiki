package com.persiangulfwiki.core.revisioncheck.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// One advisory check of one article revision (V38): the job it waits on while PENDING, and the
// worker's findings once DONE, copied here because the worker deletes finished job rows after
// 30 days and a revision can wait longer than that for a moderator. Reset in place by every
// submit (RevisionCheckService), so a revision has at most one row per check type.
//
// revisionId is a plain UUID column rather than an association, matching ModerationTask's: it
// points across a feature boundary.
@Entity
@Table(name = "revision_checks")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class RevisionCheck extends AuditableEntity {

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 20, updatable = false)
    private RevisionCheckType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RevisionCheckStatus status;

    // The jobs row (V37) a PENDING check waits on; a finished row is applied only while it is
    // still the one named here. Set null by the worker's clean-up once the job row is deleted.
    @Column(name = "job_id")
    private @Nullable Long jobId;

    @Column(name = "reason_code", length = 60)
    private @Nullable String reasonCode;

    // See V38 for what each count means per check type. All three null unless DONE.
    @Column(name = "total_count")
    private @Nullable Integer totalCount;

    @Column(name = "problem_count")
    private @Nullable Integer problemCount;

    @Column(name = "omitted_count")
    private @Nullable Integer omittedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable RevisionCheckFindings findings;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    // Set only by the dev-profile fixture endpoint; null on every row a real submit writes.
    @Column(name = "dev_marker", length = 40)
    private @Nullable String devMarker;
}

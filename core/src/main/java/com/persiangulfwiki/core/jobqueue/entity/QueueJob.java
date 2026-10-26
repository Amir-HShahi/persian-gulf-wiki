package com.persiangulfwiki.core.jobqueue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

// One row of the worker's job queue (V37, a copy of the worker's own schema). Core only ever
// inserts these; the worker claims, runs and finishes them, and deletes finished ones after
// JOBS_KEEP_DONE / JOBS_KEEP_FAILED. Only the columns core reads or sets are mapped -- the
// worker's lock and attempt bookkeeping is none of core's business.
//
// @DynamicInsert so a field left null is left out of the INSERT and the column's own default
// applies (status 'pending', run_after now(), timestamps now()): the worker's schema owns those
// defaults, and copying them here would let the two drift. @DynamicUpdate for the one path that
// updates a row (the dev fixture writing a fake result), so it never rewrites a column the
// worker may be changing at the same moment.
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamicInsert
@DynamicUpdate
public class QueueJob {

    // The row's lifecycle, as the worker writes it (ck_jobs_status).
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A JobType's wire name. Free text in the schema on purpose -- see V37.
    @Column(nullable = false, updatable = false)
    private String type;

    // JSON text, mapped as a String for the same reason as ArticleRevision.body.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column
    private @Nullable String status;

    // Not claimable before this. Null on insert means now(); only the dev fixture sets it, to
    // park a job the worker must never pick up.
    @Column(name = "run_after")
    private @Nullable Instant runAfter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable String result;

    @Column(name = "last_error")
    private @Nullable String lastError;

    @Column(name = "created_at", updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "updated_at")
    private @Nullable Instant updatedAt;
}

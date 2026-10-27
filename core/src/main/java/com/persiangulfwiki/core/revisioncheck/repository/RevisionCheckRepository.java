package com.persiangulfwiki.core.revisioncheck.repository;

import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;

import jakarta.persistence.LockModeType;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RevisionCheckRepository extends JpaRepository<RevisionCheck, UUID> {

    Optional<RevisionCheck> findByRevisionIdAndType(UUID revisionId, RevisionCheckType type);

    List<RevisionCheck> findByRevisionId(UUID revisionId);

    // Serializes a finished job being applied against a resubmit resetting the same row, and two
    // core instances applying the same job.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RevisionCheck c where c.id = :id")
    Optional<RevisionCheck> findByIdForUpdate(@Param("id") UUID id);

    // PENDING checks whose job has finished, oldest finish first -- the same shape as
    // ArticleMediaRepository.findFinishedProcessingJobs, and for the same reason nothing has to be
    // acknowledged: an applied check leaves PENDING.
    @Query(value = """
            select c.id as checkId, j.id as jobId, j.status as jobStatus,
                   cast(j.result as text) as result, j.last_error as lastError
            from revision_checks c
            join jobs j on j.id = c.job_id
            where c.status = 'PENDING'
              and j.status in ('done', 'failed')
            order by j.updated_at, j.id
            limit :limit
            """, nativeQuery = true)
    List<FinishedCheckJob> findFinishedCheckJobs(@Param("limit") int limit);

    interface FinishedCheckJob {

        UUID getCheckId();

        Long getJobId();

        // The row's lifecycle status, "done" or "failed" -- not the result's outcome.
        String getJobStatus();

        @Nullable String getResult();

        @Nullable String getLastError();
    }

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

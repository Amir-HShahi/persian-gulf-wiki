package com.persiangulfwiki.core.jobqueue.repository;

import com.persiangulfwiki.core.jobqueue.entity.QueueJob;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface QueueJobRepository extends JpaRepository<QueueJob, Long> {

    // The dev fixtures mark the job rows they mint in the payload, the one place the worker's
    // schema leaves for it (the worker ignores payload fields it does not know). These three are
    // the only queries that look at the marker, so a job core enqueued for real can never match.
    @Query(value = """
            select exists (select 1 from jobs
                           where id = :id and payload ->> 'dev_marker' = :marker and status = 'pending')
            """, nativeQuery = true)
    boolean isPendingDevJob(@Param("id") long id, @Param("marker") String marker);

    @Modifying
    @Query(value = "delete from jobs where id = :id and payload ->> 'dev_marker' = :marker", nativeQuery = true)
    int deleteDevJob(@Param("id") long id, @Param("marker") String marker);

    @Modifying
    @Query(value = "delete from jobs where payload ->> 'dev_marker' = :marker and created_at < :threshold",
            nativeQuery = true)
    int deleteByDevMarkerCreatedBefore(@Param("marker") String marker, @Param("threshold") Instant threshold);
}

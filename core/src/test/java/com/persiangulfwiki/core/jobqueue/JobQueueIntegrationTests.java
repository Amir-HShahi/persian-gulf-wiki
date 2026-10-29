package com.persiangulfwiki.core.jobqueue;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Core's side of the worker's queue against the real jobs table (V37): what an enqueued row
// looks like to the worker, and that a row only ever exists together with the change that needed
// it. @AutoConfigureMockMvc only so the context is the one the flow tests already started.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JobQueueIntegrationTests {

    @Autowired
    private JobQueueService jobQueueService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<JobQueueTestSupport.QueuedJob> jobsMarked(String marker) {
        return JobQueueTestSupport.jobsWhere(jdbcTemplate, "test_marker", marker);
    }

    // Core sets only type and payload; the worker's schema supplies everything else, so the row
    // is claimable at once and carries none of the worker's bookkeeping.
    @Test
    void anEnqueuedJobIsAPendingRowWithTheWorkersDefaults() {
        String marker = UUID.randomUUID().toString();

        Long id = transactionTemplate.execute(_ ->
                jobQueueService.enqueue(JobType.NORMALIZE, Map.of("revision_id", "r", "test_marker", marker)));

        assertThat(jobsMarked(marker)).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(id);
            assertThat(job.type()).isEqualTo("normalize");
            assertThat(job.status()).isEqualTo("pending");
        });
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select attempts, max_attempts, run_after <= now() as is_due, locked_by, locked_until, result,
                       last_error, created_at is not null as has_created_at
                from jobs where id = ?
                """, id);
        assertThat(row).containsEntry("attempts", 0).containsEntry("is_due", true).containsEntry("has_created_at", true);
        assertThat(row.get("max_attempts")).isNull();
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_until")).isNull();
        assertThat(row.get("result")).isNull();
        assertThat(row.get("last_error")).isNull();
    }

    // MANDATORY: a job outside the caller's transaction could commit without the change that
    // needs it, so it is refused rather than given a transaction of its own.
    @Test
    void enqueueOutsideATransactionIsRefusedAndInsertsNothing() {
        String marker = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jobQueueService.enqueue(JobType.LINKS, Map.of("test_marker", marker)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jobsMarked(marker)).isEmpty();
    }

    @Test
    void aJobRollsBackWithItsCallersTransaction() {
        String marker = UUID.randomUUID().toString();

        transactionTemplate.executeWithoutResult(transaction -> {
            jobQueueService.enqueue(JobType.IMAGE, Map.of("test_marker", marker));
            assertThat(jobsMarked(marker)).hasSize(1);
            transaction.setRollbackOnly();
        });

        assertThat(jobsMarked(marker)).isEmpty();
    }
}

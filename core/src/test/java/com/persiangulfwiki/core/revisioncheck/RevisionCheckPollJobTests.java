package com.persiangulfwiki.core.revisioncheck;

import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository.FinishedCheckJob;
import com.persiangulfwiki.core.revisioncheck.service.RevisionCheckService;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevisionCheckPollJobTests {

    @Mock
    private RevisionCheckRepository revisionCheckRepository;

    @Mock
    private RevisionCheckService revisionCheckService;

    private RevisionCheckPollJob revisionCheckPollJob;

    @BeforeEach
    void setUp() {
        revisionCheckPollJob = new RevisionCheckPollJob(revisionCheckRepository, revisionCheckService);
    }

    private record Finished(UUID getCheckId, Long getJobId, String getJobStatus, @Nullable String getResult,
            @Nullable String getLastError) implements FinishedCheckJob {
    }

    // Each row is its own transaction: one that throws stays PENDING for the next run and never
    // holds back the rows after it.
    @Test
    void appliesEachFinishedRowAndLeavesOneWhoseApplyThrowsForTheNextRun() {
        Finished broken = new Finished(UUID.randomUUID(), 1L, "done", "{}", null);
        Finished failed = new Finished(UUID.randomUUID(), 2L, "failed", null, "time limit");
        when(revisionCheckRepository.findFinishedCheckJobs(anyInt())).thenReturn(List.of(broken, failed));
        doThrow(new DataAccessResourceFailureException("connection reset")).when(revisionCheckService)
                .applyResult(broken.getCheckId(), 1L, "done", "{}", null);

        assertThat(revisionCheckPollJob.poll()).isEqualTo(1);

        verify(revisionCheckService).applyResult(failed.getCheckId(), 2L, "failed", null, "time limit");
    }

    @Test
    void theScheduledRunSwallowsAFailedQuery() {
        when(revisionCheckRepository.findFinishedCheckJobs(anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatNoException().isThrownBy(revisionCheckPollJob::pollOnSchedule);
    }
}

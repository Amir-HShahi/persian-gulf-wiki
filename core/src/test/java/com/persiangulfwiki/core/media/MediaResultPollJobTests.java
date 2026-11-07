package com.persiangulfwiki.core.media;

import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository.FinishedProcessingJob;
import com.persiangulfwiki.core.media.service.MediaProcessingService;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaResultPollJobTests {

    @Mock
    private ArticleMediaRepository articleMediaRepository;

    @Mock
    private MediaProcessingService mediaProcessingService;

    private MediaResultPollJob mediaResultPollJob;

    @BeforeEach
    void setUp() {
        mediaResultPollJob = new MediaResultPollJob(articleMediaRepository, mediaProcessingService);
    }

    private record Finished(UUID getMediaId, Long getJobId, String getJobStatus, @Nullable String getResult,
            @Nullable String getLastError) implements FinishedProcessingJob {
    }

    private static Finished done(long jobId) {
        return new Finished(UUID.randomUUID(), jobId, "done", "{\"job_id\": " + jobId + "}", null);
    }

    @Test
    void appliesEveryFinishedRowAndCountsThem() {
        Finished first = done(1);
        Finished failed = new Finished(UUID.randomUUID(), 2L, "failed", null, "gave up");
        when(articleMediaRepository.findFinishedProcessingJobs(anyInt())).thenReturn(List.of(first, failed));

        assertThat(mediaResultPollJob.poll()).isEqualTo(2);

        verify(mediaProcessingService).applyResult(first.getMediaId(), 1L, "done", first.getResult(), null);
        verify(mediaProcessingService).applyResult(failed.getMediaId(), 2L, "failed", null, "gave up");
    }

    // Each row is its own transaction: one that throws is left for the next run and never holds
    // back the rows after it.
    @Test
    void aRowWhoseApplyThrowsIsLeftForTheNextRunWithoutHoldingUpTheOthers() {
        Finished broken = done(1);
        Finished fine = done(2);
        when(articleMediaRepository.findFinishedProcessingJobs(anyInt())).thenReturn(List.of(broken, fine));
        doThrow(new DataAccessResourceFailureException("connection reset")).when(mediaProcessingService)
                .applyResult(broken.getMediaId(), 1L, "done", broken.getResult(), null);

        assertThat(mediaResultPollJob.poll()).isEqualTo(1);

        verify(mediaProcessingService).applyResult(fine.getMediaId(), 2L, "done", fine.getResult(), null);
    }

    // A unique-index race on the verified hash: applying again sees the winner and records a
    // duplicate, so it is retried at once, and only once.
    @Test
    void aUniqueIndexRaceIsRetriedOnceAtOnce() {
        Finished raced = done(1);
        when(articleMediaRepository.findFinishedProcessingJobs(anyInt())).thenReturn(List.of(raced));
        doThrow(new DataIntegrityViolationException("uq_article_media_article_sha256"))
                .doNothing()
                .when(mediaProcessingService).applyResult(raced.getMediaId(), 1L, "done", raced.getResult(), null);

        assertThat(mediaResultPollJob.poll()).isEqualTo(1);

        verify(mediaProcessingService, times(2)).applyResult(raced.getMediaId(), 1L, "done", raced.getResult(), null);
    }

    @Test
    void aRaceThatRepeatsOnTheRetryIsLeftForTheNextRun() {
        Finished raced = done(1);
        when(articleMediaRepository.findFinishedProcessingJobs(anyInt())).thenReturn(List.of(raced));
        doThrow(new DataIntegrityViolationException("again")).when(mediaProcessingService)
                .applyResult(raced.getMediaId(), 1L, "done", raced.getResult(), null);

        assertThat(mediaResultPollJob.poll()).isZero();

        verify(mediaProcessingService, times(2)).applyResult(raced.getMediaId(), 1L, "done", raced.getResult(), null);
    }

    // The scheduled trigger never lets a failure escape into the scheduler.
    @Test
    void theScheduledRunSwallowsAFailedQuery() {
        when(articleMediaRepository.findFinishedProcessingJobs(anyInt()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatNoException().isThrownBy(mediaResultPollJob::pollOnSchedule);
    }
}

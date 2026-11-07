package com.persiangulfwiki.core.media;

import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository.FinishedProcessingJob;
import com.persiangulfwiki.core.media.service.MediaProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

// Reads finished pipeline jobs back onto their gallery items: every few seconds, every item still
// PROCESSING whose jobs row is done or failed (ArticleMediaRepository.findFinishedProcessingJobs)
// goes through MediaProcessingService.applyResult, each in its own transaction, so one bad row
// never holds back the others. Replaces the Redis result-stream consumer.
//
// There is nothing to acknowledge: an applied item leaves PROCESSING and stops matching. A row
// whose apply threw (the database, typically) still matches, so the next run simply tries again;
// an item whose row never applies is eventually failed by the stuck-processing sweep. Several
// core instances may poll the same rows at once -- the item's row lock and job-id check make the
// second one a no-op.
//
// Scheduling is enabled globally by OAuth2CleanupConfig.
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaResultPollJob {

    // Per run; a backlog drains over the following runs, oldest finish first.
    private static final int BATCH = 50;

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaProcessingService mediaProcessingService;

    // Fixed-delay, so a slow run never overlaps the next.
    @Scheduled(fixedDelayString = "${app.job-queue.result-poll-interval-seconds}",
            initialDelayString = "${app.job-queue.result-poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    public void pollOnSchedule() {
        try {
            poll();
        } catch (RuntimeException ex) {
            log.warn("polling finished media jobs failed; retrying next run: {}", ex.toString());
        }
    }

    // Split from the trigger so tests can apply finished jobs without waiting for the schedule.
    // Returns how many rows were handled (applied or deliberately ignored).
    public int poll() {
        List<FinishedProcessingJob> finished = articleMediaRepository.findFinishedProcessingJobs(BATCH);
        int handled = 0;
        for (FinishedProcessingJob job : finished) {
            try {
                apply(job);
                handled++;
            } catch (RuntimeException ex) {
                log.warn("could not apply job {} to media {}; retrying next run", job.getJobId(), job.getMediaId(), ex);
            }
        }
        return handled;
    }

    // A unique-index violation means another item in the same article committed the same
    // verified hash between this result's duplicate check and its write. Applying again sees
    // that item and records this one as a duplicate, so it is retried at once rather than on
    // the next run.
    private void apply(FinishedProcessingJob job) {
        try {
            applyOnce(job);
        } catch (DataIntegrityViolationException ex) {
            applyOnce(job);
        }
    }

    private void applyOnce(FinishedProcessingJob job) {
        mediaProcessingService.applyResult(job.getMediaId(), job.getJobId(), job.getJobStatus(), job.getResult(),
                job.getLastError());
    }
}

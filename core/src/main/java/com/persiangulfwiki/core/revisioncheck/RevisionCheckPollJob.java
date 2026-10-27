package com.persiangulfwiki.core.revisioncheck;

import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository.FinishedCheckJob;
import com.persiangulfwiki.core.revisioncheck.service.RevisionCheckService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

// Copies finished check jobs onto their revision_checks rows: every PENDING row whose jobs row is
// done or failed goes through RevisionCheckService.applyResult, each in its own transaction. The
// same shape as media's MediaResultPollJob, and for the same reasons nothing is acknowledged and
// several instances may poll at once. A row whose apply keeps throwing stays PENDING; nothing
// times a check out, since a check never blocks anything.
//
// Scheduling is enabled globally by OAuth2CleanupConfig.
@Slf4j
@Component
@RequiredArgsConstructor
public class RevisionCheckPollJob {

    // Per run; a backlog drains over the following runs, oldest finish first.
    private static final int BATCH = 50;

    private final RevisionCheckRepository revisionCheckRepository;
    private final RevisionCheckService revisionCheckService;

    // Fixed-delay, so a slow run never overlaps the next.
    @Scheduled(fixedDelayString = "${app.job-queue.result-poll-interval-seconds}",
            initialDelayString = "${app.job-queue.result-poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    public void pollOnSchedule() {
        try {
            poll();
        } catch (RuntimeException ex) {
            log.warn("polling finished revision check jobs failed; retrying next run: {}", ex.toString());
        }
    }

    // Split from the trigger so tests can apply finished jobs without waiting for the schedule.
    // Returns how many rows were handled (applied or deliberately ignored).
    public int poll() {
        List<FinishedCheckJob> finished = revisionCheckRepository.findFinishedCheckJobs(BATCH);
        int handled = 0;
        for (FinishedCheckJob job : finished) {
            try {
                revisionCheckService.applyResult(job.getCheckId(), job.getJobId(), job.getJobStatus(), job.getResult(),
                        job.getLastError());
                handled++;
            } catch (RuntimeException ex) {
                log.warn("could not apply job {} to revision check {}; retrying next run", job.getJobId(),
                        job.getCheckId(), ex);
            }
        }
        return handled;
    }
}

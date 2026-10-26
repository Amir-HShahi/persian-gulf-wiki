package com.persiangulfwiki.core.jobqueue.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

// Core's side of the worker's queue: putting a job on it, and reading a finished job's result.
// Each feature reads its own finished jobs by joining its rows to `jobs` (see
// ArticleMediaRepository.findFinishedProcessingJobs), so there is no generic "poll" here.
@Slf4j
@Service
@RequiredArgsConstructor
public class JobQueueService {

    private final QueueJobRepository queueJobRepository;
    private final ObjectMapper objectMapper;

    // MANDATORY rather than REQUIRED, and never REQUIRES_NEW: the point of a Postgres queue is
    // that the job row commits or rolls back with the change that needs it (an item moving to
    // PROCESSING, a revision moving to PENDING), so an enqueue outside the caller's transaction
    // is a bug, and this refuses it. The insert fires NOTIFY jobs, which Postgres delivers only
    // on commit -- the worker is never woken for a row it cannot see yet.
    //
    // Returns the row's id, which the caller stores: the finished result is found by it.
    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(JobType type, Map<String, ?> payload) {
        QueueJob job = queueJobRepository.save(QueueJob.builder()
                .type(type.wireName())
                .payload(objectMapper.writeValueAsString(payload))
                .build());
        log.info("queued {} job {}", type.wireName(), job.getId());
        return job.getId();
    }

    // Empty when there is no result or it is not readable JSON of the result's shape; the
    // caller decides what that means for its row and logs it with that context.
    public Optional<JobResult> readResult(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, JobResult.class));
        } catch (JacksonException ex) {
            log.debug("unreadable job result: {}", ex.getOriginalMessage());
            return Optional.empty();
        }
    }
}

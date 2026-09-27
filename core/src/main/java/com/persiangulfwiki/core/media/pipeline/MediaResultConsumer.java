package com.persiangulfwiki.core.media.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.persiangulfwiki.core.media.service.MediaProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Reads the Go worker's results off the submission-results stream (one JSON queue.Result in
// each entry's "result" field) and hands each to MediaProcessingService. The stream and its
// consumer group are the contract in docs/MEDIA_PIPELINE.md.
//
// Delivery is at-least-once, by construction: an entry is acknowledged only after its result
// has been applied and committed. Three outcomes per entry:
//   - applied (or deliberately ignored)   -> XACK.
//   - unreadable (not JSON, no result)    -> logged and XACKed; retrying cannot fix it.
//   - applying threw (database down, ...) -> left pending. reclaimStale picks it up again once
//     it has sat idle, from this consumer or from one that died, and gives up after
//     app.media.result-max-deliveries -- the item then stays PROCESSING until the
//     stuck-processing sweep fails it, so a poison entry costs one item, not the stream.
//
// A hand-rolled read loop rather than Spring's StreamMessageListenerContainer: that container
// retries a failed read immediately and in a tight loop, so a Redis outage would turn into a
// log flood; this loop backs off. Every instance joins the same consumer group under its own
// random name, so entries are spread across instances and never processed by two at once
// (except after a reclaim, which the item's row lock and job_id check make harmless).
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaResultConsumer implements SmartLifecycle {

    // The stream-entry field the JSON result travels in.
    public static final String RESULT_FIELD = "result";

    private static final Duration READ_BLOCK = Duration.ofSeconds(2);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);
    private static final int READ_BATCH = 10;
    private static final int RECLAIM_BATCH = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MediaProcessingService mediaProcessingService;

    @Value("${app.media.result-stream}")
    private final String resultStream;

    @Value("${app.media.result-consumer-group}")
    private final String consumerGroup;

    @Value("${app.media.result-reclaim-idle-minutes}")
    private final long reclaimIdleMinutes;

    @Value("${app.media.result-max-deliveries}")
    private final long maxDeliveries;

    // Random per process: two instances sharing a name would share one pending list and could
    // each think the other's in-flight entries were theirs. Names of stopped instances linger
    // in XINFO CONSUMERS; that is harmless, and reclaimStale moves anything they left pending.
    private final String consumerName = "core-" + UUID.randomUUID();

    // Lifecycle state, written by start/stop on the container's thread and read by the loop.
    private volatile boolean isRunning;
    private @Nullable Thread readLoop;

    @Override
    public void start() {
        isRunning = true;
        readLoop = Thread.ofPlatform().name("media-result-consumer").daemon(true).start(this::readUntilStopped);
    }

    // No interrupt: the loop may be inside a database transaction, and a blocked read returns
    // on its own within READ_BLOCK.
    @Override
    public void stop() {
        isRunning = false;
        Thread loop = readLoop;
        if (loop != null) {
            try {
                loop.join(READ_BLOCK.plus(ERROR_BACKOFF));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    // Also recovers entries a crashed instance was holding. Fixed-delay, so a slow run never
    // overlaps the next.
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void reclaimStaleOnSchedule() {
        if (!isRunning) {
            return;
        }
        try {
            reclaimStale(Duration.ofMinutes(reclaimIdleMinutes));
        } catch (RuntimeException ex) {
            log.warn("reclaiming pending pipeline results failed; retrying next run: {}", ex.toString());
        }
    }

    // Split from the trigger, taking the idle threshold, so tests can reclaim immediately.
    // Returns how many entries were claimed and handled.
    public int reclaimStale(Duration minIdle) {
        StreamOperations<String, Object, Object> streams = redisTemplate.opsForStream();
        int handled = 0;
        for (PendingMessage pending : streams.pending(resultStream, consumerGroup, Range.unbounded(), RECLAIM_BATCH)) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            if (pending.getTotalDeliveryCount() >= maxDeliveries) {
                log.error("giving up on pipeline result entry {} after {} deliveries", pending.getId(),
                        pending.getTotalDeliveryCount());
                acknowledge(pending.getId());
                continue;
            }
            // minIdle again here, so an entry another instance claimed a moment ago is not
            // taken from it.
            List<MapRecord<String, Object, Object>> claimed =
                    streams.claim(resultStream, consumerGroup, consumerName, minIdle, pending.getId());
            claimed.forEach(this::handle);
            handled += claimed.size();
        }
        return handled;
    }

    private void readUntilStopped() {
        boolean hasGroup = false;
        while (isRunning) {
            try {
                if (!hasGroup) {
                    createGroupIfMissing();
                    hasGroup = true;
                }
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(consumerGroup, consumerName),
                        StreamReadOptions.empty().count(READ_BATCH).block(READ_BLOCK),
                        StreamOffset.create(resultStream, ReadOffset.lastConsumed()));
                if (records != null) {
                    records.forEach(this::handle);
                }
            } catch (RuntimeException ex) {
                if (!isRunning) {
                    return;
                }
                // The group is re-checked after any failure: it may be what went missing (a
                // flushed or restarted Redis without persistence).
                hasGroup = false;
                log.warn("reading pipeline results failed; retrying in {}: {}", ERROR_BACKOFF, ex.toString());
                backOff();
            }
        }
    }

    // MKSTREAM, so core can start before the worker has ever written a result. Offset "0"
    // rather than "$" so results written before this group first existed are still read --
    // applying one twice is harmless, missing one strands an item in PROCESSING.
    private void createGroupIfMissing() {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                createGroup(connection);
                return null;
            });
        } catch (DataAccessException ex) {
            String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw ex;
            }
        }
    }

    private void createGroup(RedisConnection connection) {
        connection.streamCommands().xGroupCreate(resultStream.getBytes(StandardCharsets.UTF_8), consumerGroup,
                ReadOffset.from("0"), true);
    }

    private void handle(MapRecord<String, Object, Object> record) {
        PipelineResult result = parse(record);
        if (result == null) {
            acknowledge(record.getId());
            return;
        }
        try {
            apply(result);
            acknowledge(record.getId());
        } catch (RuntimeException ex) {
            log.warn("could not apply pipeline result entry {} (job {}); left pending for retry", record.getId(),
                    result.jobId(), ex);
        }
    }

    // A unique-index violation means another item in the same article committed the same
    // verified hash between this result's duplicate check and its write. Applying again sees
    // that item and records this one as a duplicate, so it is retried at once rather than
    // left for a reclaim.
    private void apply(PipelineResult result) {
        try {
            mediaProcessingService.applyResult(result);
        } catch (DataIntegrityViolationException ex) {
            mediaProcessingService.applyResult(result);
        }
    }

    private @Nullable PipelineResult parse(MapRecord<String, Object, Object> record) {
        Object json = record.getValue().get(RESULT_FIELD);
        if (!(json instanceof String text)) {
            log.error("dropping pipeline result entry {}: no {} field", record.getId(), RESULT_FIELD);
            return null;
        }
        try {
            PipelineResult result = objectMapper.readValue(text, PipelineResult.class);
            if (result == null) {
                log.error("dropping pipeline result entry {}: empty result", record.getId());
            }
            return result;
        } catch (JacksonException ex) {
            log.error("dropping pipeline result entry {}: not a readable result ({})", record.getId(),
                    ex.getOriginalMessage());
            return null;
        }
    }

    private void acknowledge(RecordId id) {
        redisTemplate.opsForStream().acknowledge(resultStream, consumerGroup, id);
    }

    private void backOff() {
        try {
            Thread.sleep(ERROR_BACKOFF);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            isRunning = false;
        }
    }
}

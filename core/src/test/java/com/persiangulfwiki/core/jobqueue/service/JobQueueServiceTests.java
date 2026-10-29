package com.persiangulfwiki.core.jobqueue.service;

import tools.jackson.databind.json.JsonMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobQueueServiceTests {

    @Mock
    private QueueJobRepository queueJobRepository;

    private JobQueueService jobQueueService;

    @BeforeEach
    void setUp() {
        jobQueueService = new JobQueueService(queueJobRepository, JsonMapper.builder().build());
    }

    // --- enqueue ---

    // Status, run_after and the timestamps are left null so @DynamicInsert omits them and the
    // worker's schema defaults apply.
    @Test
    void enqueueInsertsTheWireTypeAndJsonPayloadAndReturnsTheRowId() {
        when(queueJobRepository.save(any(QueueJob.class))).thenAnswer(invocation -> {
            QueueJob job = invocation.getArgument(0);
            job.setId(42L);
            return job;
        });

        long id = jobQueueService.enqueue(JobType.PANORAMA, Map.of("submission_id", "abc", "declared_bytes", 7));

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<QueueJob> saved = ArgumentCaptor.forClass(QueueJob.class);
        verify(queueJobRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("panorama");
        assertThat((String) JsonPath.read(saved.getValue().getPayload(), "$.submission_id")).isEqualTo("abc");
        assertThat((Integer) JsonPath.read(saved.getValue().getPayload(), "$.declared_bytes")).isEqualTo(7);
        assertThat(saved.getValue().getStatus()).isNull();
        assertThat(saved.getValue().getRunAfter()).isNull();
        assertThat(saved.getValue().getCreatedAt()).isNull();
    }

    // --- readResult ---

    @Test
    void readResultParsesTheWorkersImageEnvelope() {
        Optional<JobResult> result = jobQueueService.readResult("""
                {"version": 1, "job_id": 42, "type": "image", "outcome": "ok", "attempt": 2,
                 "image": {"width": 3000, "height": 4000, "format": "jpeg",
                           "sha256": "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
                           "placeholder": "data:image/webp;base64,UklGRjAAAABXRUJQVlA4",
                           "attribution": {"author": "Maryam", "license_url": "https://example.org/l",
                                           "created_at": "2024-05-01T10:20:30"},
                           "variants": [{"label": "w320", "format": "webp", "key": "pending/x/w320.webp",
                                         "width": 320, "height": 427, "bytes": 11874}],
                           "original": {"key": "originals/x/original.jpg", "bytes": 99,
                                        "content_type": "image/jpeg"}}}
                """);

        assertThat(result).hasValueSatisfying(parsed -> {
            assertThat(parsed.version()).isEqualTo(JobResult.CONTRACT_VERSION);
            assertThat(parsed.jobId()).isEqualTo(42L);
            assertThat(parsed.type()).isEqualTo("image");
            assertThat(parsed.outcome()).isEqualTo(JobResult.OUTCOME_OK);
            assertThat(parsed.attempt()).isEqualTo(2);
            assertThat(parsed.image()).isNotNull();
            assertThat(parsed.image().attribution()).isEqualTo(
                    new JobResult.Attribution("Maryam", null, null, null, "https://example.org/l", "2024-05-01T10:20:30"));
            assertThat(parsed.image().variants()).singleElement().isEqualTo(
                    new JobResult.Variant("w320", "webp", "pending/x/w320.webp", 11874L, 320, 427));
            assertThat(parsed.image().original())
                    .isEqualTo(new JobResult.Original("originals/x/original.jpg", 99L, "image/jpeg"));
            assertThat(parsed.normalize()).isNull();
            assertThat(parsed.links()).isNull();
        });
    }

    @Test
    void readResultParsesTheNormalizeAndLinksBlocks() {
        JobResult normalize = jobQueueService.readResult("""
                {"version": 1, "job_id": 7, "type": "normalize", "outcome": "ok",
                 "normalize": {"revision_id": "r", "locale": "fa", "count": 3, "findings_truncated": 2,
                               "findings": [{"rule": "arabic_yeh", "line": 1, "column": 3, "offset": 4,
                                             "found": "ي", "suggest": "ی", "context": "جزيره"}]}}
                """).orElseThrow();
        assertThat(normalize.normalize()).isEqualTo(new JobResult.Normalize("r", "fa", 3,
                List.of(new JobResult.Finding("arabic_yeh", 1, 3, 4, "ي", "ی", "جزيره")), 2));

        JobResult links = jobQueueService.readResult("""
                {"version": 1, "job_id": 8, "type": "links", "outcome": "ok",
                 "links": {"revision_id": "r", "count": 1, "broken": 1, "links_truncated": 0,
                           "links": [{"url": "https://e.org/x", "status": "broken", "http_status": 404,
                                      "archive_url": "https://web.archive.org/x", "error": null}]}}
                """).orElseThrow();
        assertThat(links.links()).isNotNull();
        assertThat(links.links().broken()).isEqualTo(1);
        assertThat(links.links().links()).singleElement().isEqualTo(
                new JobResult.Link("https://e.org/x", "broken", 404, null, "https://web.archive.org/x", null));
    }

    // The worker adds fields (duration_ms, finished_at, a later job type's block) without core
    // being redeployed; none of them may make a result unreadable.
    @Test
    void readResultIgnoresFieldsItDoesNotKnowAtEveryLevel() {
        Optional<JobResult> result = jobQueueService.readResult("""
                {"version": 1, "job_id": 1, "type": "image", "outcome": "ok", "duration_ms": 812,
                 "finished_at": "2026-10-12T09:30:00Z", "spreadsheet": {"rows": 3},
                 "image": {"sha256": "x", "exif_stripped": true,
                           "attribution": {"author": "A", "rights_holder": "B"},
                           "variants": [{"label": "w320", "key": "k", "codec": "vp8"}],
                           "original": {"key": "o", "etag": "e"}},
                 "reason": {"code": "c", "retryable": false}}
                """);

        assertThat(result).hasValueSatisfying(parsed -> {
            assertThat(parsed.jobId()).isEqualTo(1L);
            assertThat(parsed.image().attribution().author()).isEqualTo("A");
            assertThat(parsed.image().variants().getFirst().label()).isEqualTo("w320");
            assertThat(parsed.reason().code()).isEqualTo("c");
        });
    }

    // detail is for logs and its values are not all strings: a limit is a number, a size an object.
    @Test
    void readResultAcceptsAReasonDetailWithNonStringValues() {
        JobResult result = jobQueueService.readResult("""
                {"version": 1, "job_id": 3, "type": "image", "outcome": "rejected",
                 "reason": {"code": "dimensions_too_large",
                            "detail": {"limit": "pixels", "max_pixels": 100000000, "ratio": 2.5,
                                       "size": {"width": 40000, "height": 30000}, "flags": [true, null]}}}
                """).orElseThrow();

        assertThat(result.reason()).isNotNull();
        assertThat(result.reason().code()).isEqualTo("dimensions_too_large");
        assertThat(result.reason().detail())
                .containsEntry("limit", "pixels")
                .containsEntry("max_pixels", 100000000)
                .containsEntry("ratio", 2.5)
                .containsKeys("size", "flags");
    }

    @Test
    void readResultIsEmptyForNoBlankOrNullJson() {
        assertThat(jobQueueService.readResult(null)).isEmpty();
        assertThat(jobQueueService.readResult("")).isEmpty();
        assertThat(jobQueueService.readResult("   \n")).isEmpty();
        assertThat(jobQueueService.readResult("null")).isEmpty();
    }

    // Unreadable is a refused result, never an exception escaping into the poll.
    @Test
    void readResultIsEmptyForMalformedJsonOrJsonOfAnotherShape() {
        assertThat(jobQueueService.readResult("{not json")).isEmpty();
        assertThat(jobQueueService.readResult("[1, 2, 3]")).isEmpty();
        assertThat(jobQueueService.readResult("\"done\"")).isEmpty();
        assertThat(jobQueueService.readResult("{\"version\": \"one\"}")).isEmpty();
        assertThat(jobQueueService.readResult("{\"job_id\": {\"id\": 1}}")).isEmpty();
    }

    // A result with every field missing is readable; refusing it is the feature's job.
    @Test
    void readResultReturnsAnEmptyEnvelopeForAnEmptyObject() {
        assertThat(jobQueueService.readResult("{}"))
                .contains(new JobResult(null, null, null, null, null, null, null, null, null));
    }
}

package com.persiangulfwiki.core.dev;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.event.RevisionSubmittedEvent;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestArticleResponse;
import com.persiangulfwiki.core.dev.dto.DevTestJobOutcome;
import com.persiangulfwiki.core.dev.dto.DevTestJobResultRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaResponse;
import com.persiangulfwiki.core.dev.dto.DevTestRevisionCheckRequest;
import com.persiangulfwiki.core.dev.dto.DevTestRevisionCheckResponse;
import com.persiangulfwiki.core.jobqueue.JobQueueTestSupport;
import com.persiangulfwiki.core.jobqueue.JobQueueTestSupport.QueuedJob;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.media.MediaResultPollJob;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaAttribution;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.revisioncheck.dto.RevisionChecksResponse;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;
import com.persiangulfwiki.core.revisioncheck.service.RevisionCheckService;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The dev fixtures that drive the pipeline without a worker: a gallery item minted with a parked
// jobs row, the job-result route that writes the worker's answer onto it (applied by the real
// result poll), and minted revision checks. Runs under the dev profile, the only one in which the
// fixture controllers exist; asserts on the rows it minted, since the context is shared with the
// other dev-profile suites.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestPipelineEndpointIntegrationTests {

    private static final Instant PARKED_UNTIL = Instant.parse("9999-12-31T00:00:00Z");
    private static final Duration APPLIED_WITHIN = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private QueueJobRepository queueJobRepository;

    @Autowired
    private JobQueueService jobQueueService;

    @Autowired
    private MediaResultPollJob mediaResultPollJob;

    @Autowired
    private RevisionCheckRepository revisionCheckRepository;

    @Autowired
    private RevisionCheckService revisionCheckService;

    @Autowired
    private DevTestMediaSweeper mediaSweeper;

    @Autowired
    private DevTestRevisionCheckSweeper revisionCheckSweeper;

    // --- Helpers ---

    private DevTestMediaResponse mintMedia(MediaKind type, ProcessingStatus processing, Boolean withQueueJob)
            throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestMediaRequest(null, type, processing,
                                null, null, null, null, null, withQueueJob))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestMediaResponse.class);
    }

    private DevTestMediaResponse mintWithJob(MediaKind type) throws Exception {
        return mintMedia(type, ProcessingStatus.PROCESSING, true);
    }

    private ResultActions finishJob(UUID mediaId, @Nullable DevTestJobResultRequest request) throws Exception {
        return mockMvc.perform(post("/api/dev/test-media/" + mediaId + "/job-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request == null ? "" : objectMapper.writeValueAsString(request)));
    }

    private ArticleMedia pollUntil(UUID mediaId, ProcessingStatus expected) {
        mediaResultPollJob.poll();
        await().atMost(APPLIED_WITHIN).until(() ->
                articleMediaRepository.findById(mediaId).orElseThrow().getProcessingStatus() == expected);
        return articleMediaRepository.findById(mediaId).orElseThrow();
    }

    private UUID mintRevisionId() throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestArticleRequest(null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestArticleResponse.class).revisionId();
    }

    private ResultActions mintCheckRaw(String body) throws Exception {
        return mockMvc.perform(post("/api/dev/test-revision-checks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private DevTestRevisionCheckResponse mintCheck(UUID revisionId, @Nullable RevisionCheckType type,
            @Nullable RevisionCheckStatus status) throws Exception {
        String body = mintCheckRaw(objectMapper.writeValueAsString(new DevTestRevisionCheckRequest(revisionId, type,
                status)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestRevisionCheckResponse.class);
    }

    private long realJob() {
        Long id = transactionTemplate.execute(_ ->
                jobQueueService.enqueue(JobType.IMAGE, Map.of("submission_id", UUID.randomUUID().toString())));
        assertThat(id).isNotNull();
        return id;
    }

    // --- Smoke: a minted item's job, finished by the fixture, applied by the real poll ---

    @Test
    void withQueueJobMintsAParkedMarkedJobAndAnOkAnswerMakesTheItemReadyThroughTheRealPoll() throws Exception {
        DevTestMediaResponse minted = mintWithJob(MediaKind.IMAGE);

        assertThat(minted.queueJobId()).isNotNull();
        assertThat(articleMediaRepository.findById(minted.mediaId()).orElseThrow().getProcessingQueueJobId())
                .isEqualTo(minted.queueJobId());
        // Parked so a running worker never claims it, and marked so only the fixtures touch it.
        assertThat(JobQueueTestSupport.jobsForMedia(jdbcTemplate, minted.mediaId())).singleElement()
                .satisfies(job -> {
                    assertThat(job.id()).isEqualTo(minted.queueJobId());
                    assertThat(job.type()).isEqualTo("image");
                    assertThat(job.status()).isEqualTo("pending");
                    assertThat((String) JsonPath.read(job.payload(), "$.dev_marker")).isEqualTo(DevTestFixtures.MARKER);
                    assertThat((String) JsonPath.read(job.payload(), "$.object_key"))
                            .isEqualTo(minted.mediaId().toString());
                });
        assertThat(queueJobRepository.findById(minted.queueJobId()).orElseThrow().getRunAfter()).isEqualTo(PARKED_UNTIL);
        // Nothing to apply while it waits.
        mediaResultPollJob.poll();
        assertThat(articleMediaRepository.findById(minted.mediaId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);

        // No body at all is an OK answer without attribution.
        finishJob(minted.mediaId(), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mediaId").value(minted.mediaId().toString()))
                .andExpect(jsonPath("$.queueJobId").value(minted.queueJobId()))
                .andExpect(jsonPath("$.jobStatus").value("done"));

        ArticleMedia ready = pollUntil(minted.mediaId(), ProcessingStatus.READY);
        assertThat(ready.getFailureCode()).isNull();
        assertThat(ready.getSha256()).isEqualTo(ready.getDeclaredSha256());
        assertThat(ready.getVariants()).extracting("size").containsExactly("w320", "w640");
        assertThat(ready.getAttribution()).isNull();
        // The real path opened the moderation task, as a worker's answer would.
        assertThat(moderationTaskRepository.existsByMediaMetadataVersionId(minted.firstMetadataVersionId())).isTrue();

        // Answered once only.
        finishJob(minted.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.FAILED, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_TEST_MEDIA_JOB_NOT_PENDING"));
    }

    @Test
    void anOkAnswerWithAttributionAndAPanoramaItemGetThePanoramaShapedResult() throws Exception {
        DevTestMediaResponse panorama = mintWithJob(MediaKind.PANORAMA_360);
        assertThat(JobQueueTestSupport.jobsForMedia(jdbcTemplate, panorama.mediaId()))
                .extracting(QueuedJob::type).containsExactly("panorama");

        finishJob(panorama.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.OK, null, true))
                .andExpect(status().isAccepted());

        ArticleMedia ready = pollUntil(panorama.mediaId(), ProcessingStatus.READY);
        assertThat(ready.getVariants()).singleElement().satisfies(variant -> {
            assertThat(variant.size()).isEqualTo("2k");
            assertThat(variant.key()).isEqualTo(MediaProcessingService.pendingVariantPrefix(panorama.mediaId()) + "2k.webp");
        });
        assertThat(ready.getAttribution()).isEqualTo(new MediaAttribution("مریم احمدی", null, "© 2026 مریم احمدی",
                "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "2024-05-01T10:20:30"));
    }

    // The answer is exactly what the worker would write, so the real mapping applies: a code core
    // knows is kept, one it does not ends as the generic `rejected`, and giving up is
    // processing_error.
    @Test
    void rejectedAndFailedAnswersFailTheItemWithTheMatchingCode() throws Exception {
        DevTestMediaResponse defaultCode = mintWithJob(MediaKind.IMAGE);
        DevTestMediaResponse knownCode = mintWithJob(MediaKind.PANORAMA_360);
        DevTestMediaResponse unknownCode = mintWithJob(MediaKind.IMAGE);
        DevTestMediaResponse gaveUp = mintWithJob(MediaKind.IMAGE);

        finishJob(defaultCode.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.REJECTED, null, null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobStatus").value("done"));
        finishJob(knownCode.mediaId(),
                new DevTestJobResultRequest(DevTestJobOutcome.REJECTED, "panorama_aspect_ratio", null))
                .andExpect(status().isAccepted());
        finishJob(unknownCode.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.REJECTED, "object_missing", null))
                .andExpect(status().isAccepted());
        finishJob(gaveUp.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.FAILED, null, null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobStatus").value("failed"));

        assertThat(pollUntil(defaultCode.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("corrupt_file");
        assertThat(pollUntil(knownCode.mediaId(), ProcessingStatus.FAILED).getFailureCode())
                .isEqualTo("panorama_aspect_ratio");
        assertThat(pollUntil(unknownCode.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("rejected");
        assertThat(pollUntil(gaveUp.mediaId(), ProcessingStatus.FAILED).getFailureCode()).isEqualTo("processing_error");
        assertThat(queueJobRepository.findById(gaveUp.queueJobId()).orElseThrow().getLastError()).isNotBlank();
    }

    @Test
    void jobFixturesRefuseStatesTheyCannotDrive() throws Exception {
        // A job only exists for an item being processed, and only for a kind the worker handles.
        for (Object[] refused : new Object[][] {
                {MediaKind.IMAGE, ProcessingStatus.READY},
                {MediaKind.IMAGE, ProcessingStatus.UPLOADING},
                {MediaKind.VIDEO, ProcessingStatus.PROCESSING}}) {
            mockMvc.perform(post("/api/dev/test-media")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new DevTestMediaRequest(null,
                                    (MediaKind) refused[0], (ProcessingStatus) refused[1], null, null, null, null,
                                    null, true))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_MEDIA_REQUEST"));
        }
        // withQueueJob false is the same as leaving it out.
        assertThat(mintMedia(MediaKind.IMAGE, ProcessingStatus.PROCESSING, false).queueJobId()).isNull();

        // No such item, or one this endpoint did not mint: 404.
        finishJob(UUID.randomUUID(), null).andExpect(status().isNotFound());
        DevTestMediaResponse handMadeSource = mintWithJob(MediaKind.IMAGE);
        ArticleMedia handMade = articleMediaRepository.findById(handMadeSource.mediaId()).orElseThrow();
        handMade.setDevMarker(null);
        articleMediaRepository.save(handMade);
        finishJob(handMade.getId(), null).andExpect(status().isNotFound());
        assertThat(JobQueueTestSupport.jobsForMedia(jdbcTemplate, handMade.getId())).extracting(QueuedJob::status)
                .containsExactly("pending");

        // Minted without a job, or pointed at a job no fixture minted: nothing to answer.
        DevTestMediaResponse noJob = mintMedia(MediaKind.IMAGE, ProcessingStatus.PROCESSING, null);
        finishJob(noJob.mediaId(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_TEST_MEDIA_JOB_NOT_PENDING"));
        long real = realJob();
        ArticleMedia onRealJob = articleMediaRepository.findById(noJob.mediaId()).orElseThrow();
        onRealJob.setProcessingQueueJobId(real);
        articleMediaRepository.save(onRealJob);
        finishJob(noJob.mediaId(), null).andExpect(status().isConflict());
        assertThat(queueJobRepository.findById(real).orElseThrow().getStatus()).isEqualTo("pending");

        // An overlong reason code is refused before anything is written.
        DevTestMediaResponse minted = mintWithJob(MediaKind.IMAGE);
        finishJob(minted.mediaId(), new DevTestJobResultRequest(DevTestJobOutcome.REJECTED, "x".repeat(61), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_MEDIA_REQUEST"));
        assertThat(queueJobRepository.findById(minted.queueJobId()).orElseThrow().getStatus()).isEqualTo("pending");
    }

    // The delete route takes the item's own minted job with it, never a job core enqueued for
    // real, even one the item has been pointed at.
    @Test
    void deletingAMintedItemDeletesItsMintedJobAndNoOtherJob() throws Exception {
        DevTestMediaResponse minted = mintWithJob(MediaKind.IMAGE);
        mockMvc.perform(delete("/api/dev/test-media/" + minted.mediaId())).andExpect(status().isNoContent());
        assertThat(articleMediaRepository.findById(minted.mediaId())).isEmpty();
        assertThat(queueJobRepository.findById(minted.queueJobId())).isEmpty();

        long real = realJob();
        DevTestMediaResponse onRealJob = mintMedia(MediaKind.IMAGE, ProcessingStatus.PROCESSING, null);
        ArticleMedia media = articleMediaRepository.findById(onRealJob.mediaId()).orElseThrow();
        media.setProcessingQueueJobId(real);
        articleMediaRepository.save(media);
        mockMvc.perform(delete("/api/dev/test-media/" + onRealJob.mediaId())).andExpect(status().isNoContent());
        assertThat(queueJobRepository.findById(real)).isPresent();
    }

    // A parked job is never claimed and so never finished, and the worker deletes finished jobs
    // only: the sweeper is the only way out for one, and it must never match a real job.
    @Test
    void theMediaSweeperReclaimsMintedJobsAndSparesRealOnes() throws Exception {
        DevTestMediaResponse minted = mintWithJob(MediaKind.IMAGE);
        long real = realJob();

        mediaSweeper.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(queueJobRepository.findById(minted.queueJobId())).isPresent();

        mediaSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(articleMediaRepository.findById(minted.mediaId())).isEmpty();
        assertThat(queueJobRepository.findById(minted.queueJobId())).isEmpty();
        assertThat(queueJobRepository.findById(real)).isPresent();
    }

    // --- Revision check fixtures ---

    @Test
    void mintsARevisionCheckInEveryStateWithItsSampleFindings() throws Exception {
        UUID revisionId = mintRevisionId();
        DevTestRevisionCheckResponse text = mintCheck(revisionId, null, null);
        DevTestRevisionCheckResponse links = mintCheck(revisionId, RevisionCheckType.LINKS, RevisionCheckStatus.DONE);

        assertThat(text.revisionId()).isEqualTo(revisionId);
        assertThat(text.type()).isEqualTo(RevisionCheckType.NORMALIZE);
        assertThat(text.status()).isEqualTo(RevisionCheckStatus.DONE);
        RevisionCheck stored = revisionCheckRepository.findById(text.checkId()).orElseThrow();
        assertThat(stored.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
        assertThat(stored.getJobId()).isNull();

        // Read back the way a moderator gets it, so the sample is known to render.
        RevisionChecksResponse read = revisionCheckService.getForRevision(revisionId);
        assertThat(read.textCheck()).isNotNull();
        assertThat(read.textCheck().findingCount()).isEqualTo(2);
        assertThat(read.textCheck().findings()).extracting("rule").containsExactly("arabic_yeh", "detached_prefix");
        assertThat(read.linkCheck()).isNotNull();
        assertThat(read.linkCheck().checkedCount()).isEqualTo(4);
        assertThat(read.linkCheck().brokenCount()).isEqualTo(1);
        assertThat(read.linkCheck().links()).extracting("status")
                .containsExactly("ok", "redirected", "broken", "restricted");
        assertThat(read.linkCheck().links()).extracting("isBroken").containsExactly(false, false, true, false);
        assertThat(links.checkId()).isNotEqualTo(text.checkId());

        UUID otherRevision = mintRevisionId();
        mintCheck(otherRevision, RevisionCheckType.NORMALIZE, RevisionCheckStatus.REJECTED);
        mintCheck(otherRevision, RevisionCheckType.LINKS, RevisionCheckStatus.PENDING);
        RevisionChecksResponse other = revisionCheckService.getForRevision(otherRevision);
        assertThat(other.textCheck().status()).isEqualTo(RevisionCheckStatus.REJECTED);
        assertThat(other.textCheck().reasonCode()).isEqualTo("unsupported_locale");
        assertThat(other.textCheck().checkedAt()).isNotNull();
        assertThat(other.linkCheck().status()).isEqualTo(RevisionCheckStatus.PENDING);
        assertThat(other.linkCheck().checkedAt()).isNull();
        assertThat(other.linkCheck().links()).isEmpty();
    }

    @Test
    void revisionCheckFixtureRefusesWhatItCannotMint() throws Exception {
        mintCheckRaw("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_REVISION_CHECK_REQUEST"));
        mintCheckRaw("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_REVISION_CHECK_REQUEST"));
        mintCheckRaw("{\"revisionId\": \"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());

        UUID revisionId = mintRevisionId();
        mintCheck(revisionId, RevisionCheckType.LINKS, RevisionCheckStatus.FAILED);
        mintCheckRaw(objectMapper.writeValueAsString(new DevTestRevisionCheckRequest(revisionId,
                RevisionCheckType.LINKS, RevisionCheckStatus.DONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_TEST_REVISION_CHECK_EXISTS"));
    }

    // Only marked rows are ever deleted; a real submit takes a minted row over, clearing its
    // marker, after which it is a real row like any other.
    @Test
    void deletesOnlyRevisionChecksItMintedAndARealSubmitTakesOneOver() throws Exception {
        UUID revisionId = mintRevisionId();
        DevTestRevisionCheckResponse minted = mintCheck(revisionId, RevisionCheckType.NORMALIZE, null);
        mockMvc.perform(delete("/api/dev/test-revision-checks/" + minted.checkId())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-revision-checks/" + minted.checkId())).andExpect(status().isNotFound());
        assertThat(revisionCheckRepository.findById(minted.checkId())).isEmpty();

        UUID submitted = mintRevisionId();
        DevTestRevisionCheckResponse takenOver = mintCheck(submitted, RevisionCheckType.LINKS, null);
        transactionTemplate.executeWithoutResult(_ ->
                revisionCheckService.onRevisionSubmitted(new RevisionSubmittedEvent(submitted, true)));
        RevisionCheck real = revisionCheckRepository.findById(takenOver.checkId()).orElseThrow();
        assertThat(real.getDevMarker()).isNull();
        assertThat(real.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
        assertThat(real.getJobId()).isNotNull();
        assertThat(revisionCheckRepository.findByRevisionId(submitted)).hasSize(2);
        mockMvc.perform(delete("/api/dev/test-revision-checks/" + takenOver.checkId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_CHECK_NOT_FOUND"));
        mockMvc.perform(delete("/api/dev/test-revision-checks/" + UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(revisionCheckRepository.findById(takenOver.checkId())).isPresent();

        revisionCheckSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(revisionCheckRepository.findByRevisionId(submitted)).hasSize(2);
    }

    @Test
    void theRevisionCheckSweeperReclaimsAgedMintedChecksOnly() throws Exception {
        DevTestRevisionCheckResponse minted = mintCheck(mintRevisionId(), null, RevisionCheckStatus.DONE);
        UUID realRevision = mintRevisionId();
        RevisionCheck real = revisionCheckRepository.save(RevisionCheck.builder()
                .revisionId(realRevision)
                .type(RevisionCheckType.NORMALIZE)
                .status(RevisionCheckStatus.FAILED)
                .build());

        assertThat(revisionCheckSweeper.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS))).isZero();
        assertThat(revisionCheckRepository.findById(minted.checkId())).isPresent();

        assertThat(revisionCheckSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS))).isPositive();
        assertThat(revisionCheckRepository.findById(minted.checkId())).isEmpty();
        assertThat(revisionCheckRepository.findById(real.getId())).isPresent();
    }
}

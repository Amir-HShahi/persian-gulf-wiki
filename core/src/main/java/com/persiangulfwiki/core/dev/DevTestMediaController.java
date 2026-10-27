package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestJobOutcome;
import com.persiangulfwiki.core.dev.dto.DevTestJobResultRequest;
import com.persiangulfwiki.core.dev.dto.DevTestJobResultResponse;
import com.persiangulfwiki.core.dev.dto.DevTestMediaRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaResponse;
import com.persiangulfwiki.core.dev.exception.DevTestMediaJobNotPendingException;
import com.persiangulfwiki.core.dev.exception.InvalidDevTestMediaRequestException;
import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.PanoramaLink;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaUploadService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Mints a throwaway gallery item at any point in its life -- UPLOADING, PROCESSING, READY
// awaiting review, PUBLISHED, REJECTED, FAILED, HIDDEN -- plus, optionally, later metadata
// versions in any status and panorama hotspots on any version, for E2E suites that need one without driving a real upload, a
// pipeline run and a moderator decision.
//
// Two independent gates keep this out of production, exactly as for the other fixture
// controllers: @Profile("dev") here, so the bean does not exist, and DevSecurityConfig's
// profiled chain, which already matches the whole /api/dev/** prefix.
//
// Deliberately bypasses MediaUploadService and MediaModerationService: READY is only reachable
// through the pipeline and PUBLISHED/HIDDEN only through a moderator, so writing rows directly
// is what makes those states available in one call. Nothing is put in storage -- variant keys
// point at objects that do not exist, so the URLs the read path builds for them (public or
// signed) are well-formed but 404; fine for every test that reads rows rather than bytes. The
// schema's own CHECKs still hold (ck_article_media_published_has_metadata in particular),
// because a fixture the read path would have to special-case is no fixture.
//
// Items created through the *real* reserve route during an E2E run carry no marker and so can
// never be deleted here; they go away with their article, since article_media.article_id is ON
// DELETE CASCADE -- upload them to an article minted by /api/dev/test-articles.
//
// The pipeline itself can be driven too: withQueueJob mints a PROCESSING item's jobs row, parked
// (run_after in the far future) so a running worker never claims it, and marked dev_marker in its
// payload; the job-result route then writes the worker's answer onto that row, and the real
// MediaResultPollJob applies it within seconds -- validation, duplicate check, moderation tasks
// and all. Only a parked, marked row is ever written or deleted here.
@RestController
@RequestMapping("/api/dev/test-media")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Media", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestMediaController {

    // DevUserSeeder's contributor, never swept -- see DevTestArticleController.resolveAuthorUserId
    // for the sweep-ordering race a freshly minted uploader would reintroduce (uploaded_by and
    // submitted_by are both RESTRICT, V18).
    private static final String SEEDED_UPLOADER_EMAIL = "contributor@dev.local";
    private static final int SLUG_LENGTH = 12;
    private static final long FIXTURE_BYTES = 1024;
    private static final String FIXTURE_PLACEHOLDER = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
    private static final SecureRandom RANDOM = new SecureRandom();
    // Beyond any run_after the worker will reach, so its claim query never selects the row.
    private static final Instant PARKED_UNTIL = Instant.parse("9999-12-31T00:00:00Z");
    private static final String DEFAULT_REJECTION_CODE = "corrupt_file";
    private static final int MAX_REASON_CODE_LENGTH = 60;

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final PanoramaLinkRepository panoramaLinkRepository;
    private final ArticleRepository articleRepository;
    private final DevTestArticleFixtures articleFixtures;
    private final UserRepository userRepository;
    private final QueueJobRepository queueJobRepository;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Mint a disposable test gallery item", description = "Creates a gallery item marked as machine-minted on an existing "
            + "article (or, when no article id is sent, on a throwaway approved article minted with it), at any processing/publication status pair, with its first metadata version in "
            + "the status that pair implies (approved for published or hidden, rejected for rejected, "
            + "awaiting review otherwise). Optionally adds later metadata versions, one per status given "
            + "(the newest approved one becomes current), and panorama hotspots from any version (by "
            + "default the newest) to the given items. With `withQueueJob`, a PROCESSING image or panorama also "
            + "gets a pipeline job that no worker will pick up, for the job-result route to finish. No file is "
            + "stored.")
    @ApiResponse(responseCode = "201", description = "The created item and the ids of everything minted with it.")
    @ApiResponse(responseCode = "400", description = "An approved later version was asked for on an item whose first version is not "
            + "approved (it is not published or hidden), `panoramaLinkVersionNumber` names no version "
            + "being minted, a hotspot target id is blank, or `withQueueJob` was asked for on an item that is not "
            + "PROCESSING or is a VIDEO.")
    @ApiResponse(responseCode = "404", description = "The article id names no article, or a hotspot target id names no "
            + "gallery item.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO rather than the usual envelope, matching the other fixture controllers.
    public DevTestMediaResponse mint(@RequestBody(required = false) DevTestMediaRequest request) {
        DevTestMediaRequest safeRequest = request != null
                ? request
                : new DevTestMediaRequest(null, null, null, null, null, null, null, null, null);

        UUID articleId = safeRequest.articleId();
        if (articleId == null) {
            // Nothing to attach to: mint a throwaway APPROVED article (so the gallery is publicly
            // readable, like any live article) instead of making every suite set one up first.
            // It carries the dev marker, so DevTestArticleSweeper reclaims it; this route's own
            // DELETE leaves it alone because it cannot know a caller-supplied article from this
            // one, and only ever removes the media it minted.
            articleId = articleFixtures.mint(new DevTestArticleRequest(
                    null, null, null, RevisionStatus.APPROVED, null, null, null, null, null, null, null, null, null, null)).articleId();
        } else if (!articleRepository.existsById(articleId)) {
            throw new ArticleNotFoundException();
        }
        List<UUID> linkTargetIds = safeRequest.panoramaLinkTargetIds() != null
                ? safeRequest.panoramaLinkTargetIds()
                : List.of();
        for (UUID targetId : linkTargetIds) {
            // A blank string in the JSON list deserializes to null, and existsById(null) throws.
            if (targetId == null) {
                throw new InvalidDevTestMediaRequestException();
            }
            if (!articleMediaRepository.existsById(targetId)) {
                throw new MediaNotFoundException();
            }
        }

        List<MetadataVersionStatus> laterStatuses = safeRequest.laterVersionStatuses() != null
                ? safeRequest.laterVersionStatuses()
                : List.of();
        int linkVersionNumber = safeRequest.panoramaLinkVersionNumber() != null
                ? safeRequest.panoramaLinkVersionNumber()
                : laterStatuses.size() + 1;
        if (linkVersionNumber < 1 || linkVersionNumber > laterStatuses.size() + 1) {
            throw new InvalidDevTestMediaRequestException();
        }

        MediaKind type = safeRequest.type() != null ? safeRequest.type() : MediaKind.IMAGE;
        ProcessingStatus processingStatus = safeRequest.processingStatus() != null
                ? safeRequest.processingStatus()
                : ProcessingStatus.UPLOADING;
        PublicationStatus publicationStatus = safeRequest.publicationStatus() != null
                ? safeRequest.publicationStatus()
                : PublicationStatus.PENDING;
        boolean isWithQueueJob = Boolean.TRUE.equals(safeRequest.withQueueJob());
        Optional<JobType> jobType = MediaProcessingService.jobTypeFor(type);
        if (isWithQueueJob && (processingStatus != ProcessingStatus.PROCESSING || jobType.isEmpty())) {
            throw new InvalidDevTestMediaRequestException();
        }
        UUID uploaderId = resolveUploaderUserId(safeRequest.uploadedByUserId());
        String sha256 = randomSha256();
        boolean isProcessed = processingStatus == ProcessingStatus.READY;

        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(type)
                .uploadedBy(uploaderId)
                .declaredContentType(type == MediaKind.VIDEO ? "video/mp4" : "image/jpeg")
                .declaredBytes(FIXTURE_BYTES)
                .declaredSha256(sha256)
                .sha256(isProcessed ? sha256 : null)
                .processingStatus(processingStatus)
                .failureCode(processingStatus == ProcessingStatus.FAILED ? MediaFailureCode.UPLOAD_MISMATCH.code() : null)
                // Inserted PENDING and moved to its final status below, once the first metadata
                // version exists: ck_article_media_published_has_metadata refuses a PUBLISHED
                // row with no current version, and the version needs this row's id first.
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .placeholder(isProcessed ? FIXTURE_PLACEHOLDER : null)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        MetadataVersionStatus firstStatus = firstVersionStatus(publicationStatus);
        if (firstStatus != MetadataVersionStatus.APPROVED && laterStatuses.contains(MetadataVersionStatus.APPROVED)) {
            throw new InvalidDevTestMediaRequestException();
        }
        MediaMetadataVersion first = saveVersion(media.getId(), 1, uploaderId, firstStatus);
        List<MediaMetadataVersion> versions = new ArrayList<>(List.of(first));
        for (MetadataVersionStatus status : laterStatuses) {
            versions.add(saveVersion(media.getId(), versions.size() + 1, uploaderId, status));
        }
        MediaMetadataVersion current = versions.reversed().stream()
                .filter(version -> version.getStatus() == MetadataVersionStatus.APPROVED)
                .findFirst()
                .orElse(null);
        if (current != null) {
            media.setCurrentMetadataVersionId(current.getId());
            media.setPublishedAt(Instant.now());
        }
        media.setPublicationStatus(publicationStatus);
        if (isProcessed) {
            media.setVariants(fixtureVariants(media.getId(), firstStatus == MetadataVersionStatus.APPROVED));
        }
        if (isWithQueueJob) {
            media.setProcessingQueueJobId(mintParkedJob(media, jobType.get()));
        }
        articleMediaRepository.save(media);

        UUID linkVersionId = versions.get(linkVersionNumber - 1).getId();

        List<UUID> linkIds = new ArrayList<>();
        for (int i = 0; i < linkTargetIds.size(); i++) {
            linkIds.add(panoramaLinkRepository.save(PanoramaLink.builder()
                    .metadataVersionId(linkVersionId)
                    .toMediaId(linkTargetIds.get(i))
                    // Spread around the horizon so a viewer test sees distinct hotspots.
                    .yawDeg(BigDecimal.valueOf((i * 30L) % 360))
                    .pitchDeg(BigDecimal.ZERO)
                    .label("fixture link " + (i + 1))
                    .build()).getId());
        }

        return new DevTestMediaResponse(media.getId(), articleId, uploaderId, processingStatus, publicationStatus,
                first.getId(), versions.stream().skip(1).map(MediaMetadataVersion::getId).toList(),
                current != null ? current.getId() : null, List.copyOf(linkIds), media.getProcessingQueueJobId());
    }

    @Operation(summary = "Finish a minted item's pipeline job", description = "Writes the worker's answer onto the "
            + "parked job of an item minted with `withQueueJob`, exactly as the worker would. The app's own result "
            + "polling then applies it within a few seconds: OK makes the item READY (and opens its moderation "
            + "task), REJECTED and FAILED make it FAILED with the matching code. Poll the item's GET route to see "
            + "the change. An OK item's renditions point at files that do not exist, so approving it later fails "
            + "with 409. The item must not already have an answer.")
    @ApiResponse(responseCode = "202", description = "The answer is written and will be applied shortly.")
    @ApiResponse(responseCode = "400", description = "`reasonCode` is longer than 60 characters.")
    @ApiResponse(responseCode = "404", description = "No such item, or the id names one this endpoint did not mint.")
    @ApiResponse(responseCode = "409", description = "The item has no parked job waiting for an answer: it was minted "
            + "without `withQueueJob`, or an answer was already written.")
    @PostMapping("/{mediaId}/job-result")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public DevTestJobResultResponse finishJob(@PathVariable UUID mediaId,
            @RequestBody(required = false) DevTestJobResultRequest request) {
        DevTestJobResultRequest safeRequest = request != null ? request : new DevTestJobResultRequest(null, null, null);
        ArticleMedia media = articleMediaRepository.findById(mediaId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(MediaNotFoundException::new);
        Long jobId = media.getProcessingQueueJobId();
        if (jobId == null || !queueJobRepository.isPendingDevJob(jobId, DevTestFixtures.MARKER)) {
            throw new DevTestMediaJobNotPendingException();
        }
        String reasonCode = safeRequest.reasonCode() != null ? safeRequest.reasonCode() : DEFAULT_REJECTION_CODE;
        if (reasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new InvalidDevTestMediaRequestException();
        }
        DevTestJobOutcome outcome = safeRequest.outcome() != null ? safeRequest.outcome() : DevTestJobOutcome.OK;
        JobType jobType = MediaProcessingService.jobTypeFor(media.getType()).orElseThrow();

        QueueJob job = queueJobRepository.findById(jobId).orElseThrow(DevTestMediaJobNotPendingException::new);
        switch (outcome) {
            case OK -> finish(job, QueueJob.STATUS_DONE,
                    okResult(media, jobId, jobType, Boolean.TRUE.equals(safeRequest.withAttribution())), null);
            case REJECTED -> finish(job, QueueJob.STATUS_DONE, rejectedResult(jobId, jobType, reasonCode), null);
            case FAILED -> finish(job, QueueJob.STATUS_FAILED, null, "dev fixture: the worker gave up");
        }
        return new DevTestJobResultResponse(media.getId(), jobId, job.getStatus());
    }

    @Operation(summary = "Delete a minted test gallery item", description = "Deletes an item previously created by this endpoint, together "
            + "with its metadata versions, hotspots in either direction, and any moderation tasks on "
            + "them. Refuses anything else -- an item uploaded through the real flow is reported as "
            + "404 rather than deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The item was deleted.")
    @ApiResponse(responseCode = "404", description = "No such item, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID mediaId) {
        // The marker check is what makes accepting an arbitrary id safe -- see
        // DevTestSubjectController.delete. Nothing in storage to clean up: this endpoint never
        // wrote any objects.
        ArticleMedia media = articleMediaRepository.findById(mediaId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(MediaNotFoundException::new);
        Long jobId = media.getProcessingQueueJobId();
        articleMediaRepository.delete(media);
        // Only ever the item's own minted job: deleteDevJob matches the payload marker too.
        if (jobId != null) {
            queueJobRepository.deleteDevJob(jobId, DevTestFixtures.MARKER);
        }
    }

    private long mintParkedJob(ArticleMedia media, JobType jobType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submission_id", media.getId().toString());
        payload.put("object_key", MediaUploadService.stagingKey(media.getId()));
        payload.put("declared_sha256", media.getDeclaredSha256());
        payload.put("dev_marker", DevTestFixtures.MARKER);
        return queueJobRepository.save(QueueJob.builder()
                .type(jobType.wireName())
                .payload(objectMapper.writeValueAsString(payload))
                .runAfter(PARKED_UNTIL)
                .build()).getId();
    }

    private void finish(QueueJob job, String status, @Nullable String result, @Nullable String lastError) {
        job.setStatus(status);
        job.setResult(result);
        job.setLastError(lastError);
        job.setUpdatedAt(Instant.now());
        queueJobRepository.save(job);
    }

    // What the worker writes for a processed file (submission-pipeline/README.md), with variant keys
    // under the item's pending prefix and its declared hash as the verified one, so the result
    // passes MediaProcessingService's checks.
    private String okResult(ArticleMedia media, long jobId, JobType jobType, boolean isWithAttribution) {
        String prefix = MediaProcessingService.pendingVariantPrefix(media.getId());
        List<Map<String, Object>> variants = jobType == JobType.PANORAMA
                ? List.of(variant("2k", prefix + "2k.webp", 2048, 1024))
                : List.of(variant("w320", prefix + "w320.webp", 320, 240),
                        variant("w640", prefix + "w640.webp", 640, 480));
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("width", jobType == JobType.PANORAMA ? 2048 : 640);
        image.put("height", jobType == JobType.PANORAMA ? 1024 : 480);
        image.put("format", "jpeg");
        image.put("sha256", media.getDeclaredSha256());
        image.put("placeholder", FIXTURE_PLACEHOLDER);
        if (isWithAttribution) {
            image.put("attribution", Map.of(
                    "author", "مریم احمدی",
                    "copyright", "© 2026 مریم احمدی",
                    "license", "CC BY-SA 4.0",
                    "license_url", "https://creativecommons.org/licenses/by-sa/4.0/",
                    "created_at", "2024-05-01T10:20:30"));
        }
        image.put("variants", variants);
        image.put("original", Map.of("key", MediaProcessingService.originalPrefix(media.getId()) + "original.jpg",
                "bytes", FIXTURE_BYTES, "content_type", "image/jpeg"));
        Map<String, Object> result = envelope(jobId, jobType, JobResult.OUTCOME_OK);
        result.put("image", image);
        return objectMapper.writeValueAsString(result);
    }

    private String rejectedResult(long jobId, JobType jobType, String reasonCode) {
        Map<String, Object> result = envelope(jobId, jobType, JobResult.OUTCOME_REJECTED);
        result.put("reason", Map.of("code", reasonCode));
        return objectMapper.writeValueAsString(result);
    }

    private static Map<String, Object> envelope(long jobId, JobType jobType, String outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", JobResult.CONTRACT_VERSION);
        result.put("job_id", jobId);
        result.put("type", jobType.wireName());
        result.put("outcome", outcome);
        result.put("attempt", 1);
        return result;
    }

    private static Map<String, Object> variant(String label, String key, int width, int height) {
        return Map.of("label", label, "format", "webp", "key", key, "width", width, "height", height,
                "bytes", FIXTURE_BYTES);
    }

    // What the real flow would have left the first version at, given where the item ended up:
    // an item only becomes PUBLISHED (and later, possibly, HIDDEN) by its first version being
    // approved, and only becomes REJECTED by it being rejected.
    private static MetadataVersionStatus firstVersionStatus(PublicationStatus publicationStatus) {
        return switch (publicationStatus) {
            case PUBLISHED, HIDDEN -> MetadataVersionStatus.APPROVED;
            case REJECTED -> MetadataVersionStatus.REJECTED;
            case PENDING -> MetadataVersionStatus.PENDING_REVIEW;
        };
    }

    private MediaMetadataVersion saveVersion(UUID mediaId, int number, UUID submittedBy, MetadataVersionStatus status) {
        return mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                .mediaId(mediaId)
                .versionNumber(number)
                .submittedBy(submittedBy)
                .status(status)
                .build());
    }

    // Keyed where the real flow would have left the file: under the item's pending prefix until
    // it is approved, under its public prefix after (MediaVariantService.publish moves it). The
    // read path builds URLs from that layout and leaves out a variant that does not follow it.
    private static List<MediaVariant> fixtureVariants(UUID mediaId, boolean isApproved) {
        String prefix = isApproved
                ? MediaVariantService.publicVariantPrefix(mediaId)
                : MediaProcessingService.pendingVariantPrefix(mediaId);
        return new ArrayList<>(List.of(new MediaVariant("640", "webp", prefix + "640.webp", 512, 640, 320)));
    }

    // Random rather than fixed so two fixtures on one article never trip the duplicate check.
    private static String randomSha256() {
        byte[] digest = new byte[32];
        RANDOM.nextBytes(digest);
        return Base64.getEncoder().encodeToString(digest);
    }

    private UUID resolveUploaderUserId(UUID requestedUploaderUserId) {
        if (requestedUploaderUserId != null) {
            return requestedUploaderUserId;
        }
        return userRepository.findByEmail(SEEDED_UPLOADER_EMAIL)
                .map(User::getId)
                .orElseGet(this::mintDisposableUploader);
    }

    private UUID mintDisposableUploader() {
        String slug = UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        User uploader = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .enabled(true)
                .emailVerified(true)
                .build());
        return uploader.getId();
    }
}

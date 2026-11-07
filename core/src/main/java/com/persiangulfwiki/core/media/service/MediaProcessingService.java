package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaAttribution;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.event.MediaVersionAwaitingReviewEvent;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Core's half of the pipeline contract on the way back: turns one finished jobs row into the
// item's next processing state. The contract, from the worker's side, is
// submission-pipeline/README.md; core's summary is docs/MEDIA_PIPELINE.md.
//
//   done + ok       -> READY, with the verified hash, variants, placeholder and the file's
//                      attribution stored, and a moderation task opened for every metadata
//                      version awaiting review. Unless the verified hash is already live in the
//                      same article: then FAILED "duplicate".
//   done + rejected -> FAILED with the worker's reason code, if core knows it; the generic
//                      "rejected" otherwise.
//   failed          -> FAILED "processing_error". The worker marks a row failed only once it has
//                      given up retrying, so core never retries it.
//
// A row is applied only to an item that is PROCESSING *and* still names that job; anything else
// (an item deleted or timed out meanwhile, another core instance having got there first) is
// ignored.
//
// The worker is another process and its output is checked, not trusted: a result that fails
// validation, is unreadable, or is of a shape this core does not know becomes FAILED
// "processing_error" instead of being stored. Failing rather than leaving the item PROCESSING
// matters here in a way it did not with the stream: the row stays finished, so an item left
// PROCESSING would be read again on every poll until the stuck sweep gave up on it. The
// variant-key check matters most -- those keys are later copied and deleted by core, so a key
// outside the item's own pending/{mediaId}/ prefix could otherwise aim a delete at any object in
// the media bucket.
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

    // Standard padded base64 of 32 bytes: 43 significant characters and one "=".
    private static final Pattern SHA256_BASE64 = Pattern.compile("^[A-Za-z0-9+/]{43}=$");
    // Rendered by the frontend as an <img src>, so restricted to raster types: no SVG, which
    // can carry script when opened on its own.
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^data:image/(jpeg|png|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final int MAX_PLACEHOLDER_LENGTH = 16 * 1024;
    private static final int MAX_VARIANTS = 32;
    private static final int MAX_LABEL_LENGTH = 40;
    // The worker's own cap on each attribution field; anything longer is cut, not refused.
    static final int MAX_ATTRIBUTION_LENGTH = 300;
    // The longest operator-facing error text kept in a log line.
    private static final int MAX_LOGGED_ERROR_LENGTH = 500;

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final JobQueueService jobQueueService;
    private final MediaStorage mediaStorage;
    private final ApplicationEventPublisher eventPublisher;

    // Where the worker writes an item's variants; everything under it belongs to that item
    // alone, which is what lets the cleanup sweeps delete the prefix wholesale.
    public static String pendingVariantPrefix(UUID mediaId) {
        return "pending/" + mediaId + "/";
    }

    // Where the worker keeps its private, byte-for-byte copy of an item's upload, EXIF and GPS
    // included -- never served. Outside pending/ so approval leaves it alone; deleted with the
    // item by the cleanup sweeps.
    public static String originalPrefix(UUID mediaId) {
        return "originals/" + mediaId + "/";
    }

    // Empty for VIDEO: the worker has no video handler yet, so a video is queued nowhere -- see
    // MediaUploadService.complete for what that costs.
    public static Optional<JobType> jobTypeFor(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> Optional.of(JobType.IMAGE);
            case PANORAMA_360 -> Optional.of(JobType.PANORAMA);
            case VIDEO -> Optional.empty();
        };
    }

    // jobStatus is the row's own lifecycle status ("done"/"failed"), resultJson its result
    // column. Throws only for infrastructure failures (the database), which the caller retries
    // on its next poll -- including a unique-index race on the verified hash, which the retry
    // then resolves as a duplicate. Everything about the row's content is handled here and never
    // throws.
    @Transactional
    public void applyResult(UUID mediaId, long jobId, String jobStatus, @Nullable String resultJson,
            @Nullable String lastError) {
        ArticleMedia media = articleMediaRepository.findByIdForUpdate(mediaId).orElse(null);
        if (media == null) {
            log.info("ignoring job {}: media {} no longer exists", jobId, mediaId);
            return;
        }
        if (media.getProcessingStatus() != ProcessingStatus.PROCESSING
                || !Long.valueOf(jobId).equals(media.getProcessingQueueJobId())) {
            log.info("ignoring job {}: media {} is {} awaiting job {}", jobId, media.getId(),
                    media.getProcessingStatus(), media.getProcessingQueueJobId());
            return;
        }

        if (QueueJob.STATUS_FAILED.equals(jobStatus)) {
            log.warn("pipeline gave up on media {} (job {}): {}", media.getId(), jobId, truncate(lastError));
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        if (!QueueJob.STATUS_DONE.equals(jobStatus)) {
            // The poll selects finished rows only, so this is a caller bug; nothing to apply yet.
            log.error("job {} for media {} is {}, not finished", jobId, media.getId(), jobStatus);
            return;
        }

        JobResult result = jobQueueService.readResult(resultJson).orElse(null);
        if (result == null) {
            log.error("job {} for media {} finished without a readable result", jobId, media.getId());
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        if (result.version() == null || result.version() != JobResult.CONTRACT_VERSION) {
            log.error("job {} for media {} has unsupported result version {}", jobId, media.getId(), result.version());
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        if (!Long.valueOf(jobId).equals(result.jobId())) {
            log.error("job {} for media {} carries a result for job {}", jobId, media.getId(), result.jobId());
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        String expectedType = jobTypeFor(media.getType()).map(JobType::wireName).orElse(null);
        if (expectedType == null || !expectedType.equals(result.type())) {
            log.error("job {} for media {} has type {}, expected {}", jobId, media.getId(), result.type(),
                    expectedType);
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }

        switch (result.outcome() == null ? "" : result.outcome()) {
            case JobResult.OUTCOME_OK -> applyOk(media, result.image());
            case JobResult.OUTCOME_REJECTED -> applyRejected(media, result.reason());
            default -> {
                log.error("job {} for media {} has unknown outcome {}", jobId, media.getId(), result.outcome());
                fail(media, MediaFailureCode.PROCESSING_ERROR);
            }
        }
    }

    // Items no result ever came back for: the job was lost, the worker is down or stuck, or the
    // item is a VIDEO, which is queued nowhere. The item is failed so its uploader learns and its
    // pending slot frees up; a result that arrives afterwards finds it no longer PROCESSING and
    // is ignored.
    @Transactional
    public int failStuckProcessing(Instant updatedBefore) {
        int failed = articleMediaRepository.failProcessingUpdatedBefore(updatedBefore,
                MediaFailureCode.PROCESSING_TIMEOUT.code(), Instant.now());
        if (failed > 0) {
            log.warn("failed {} media item(s) stuck in PROCESSING since before {}", failed, updatedBefore);
        }
        return failed;
    }

    // Single-line text with no control characters and no bidi embeddings, overrides or isolates
    // (a file could otherwise make a name display reversed next to the uploader's), cut to the
    // worker's own 300-character cap; licenseUrl only when it is an http(s) URL, since it is
    // shown as a link. Null when nothing usable is left. Package-private for its unit tests.
    static @Nullable MediaAttribution sanitizeAttribution(JobResult.@Nullable Attribution claimed) {
        if (claimed == null) {
            return null;
        }
        String licenseUrl = cleanText(claimed.licenseUrl());
        MediaAttribution attribution = new MediaAttribution(cleanText(claimed.author()), cleanText(claimed.credit()),
                cleanText(claimed.copyright()), cleanText(claimed.license()), isWebUrl(licenseUrl) ? licenseUrl : null,
                cleanText(claimed.createdAt()));
        boolean isEmpty = Stream.of(attribution.author(), attribution.credit(), attribution.copyright(),
                        attribution.license(), attribution.licenseUrl(), attribution.createdAt())
                .allMatch(field -> field == null);
        return isEmpty ? null : attribution;
    }

    private void applyOk(ArticleMedia media, JobResult.@Nullable Image image) {
        Optional<String> problem = validate(media.getId(), image);
        if (problem.isPresent()) {
            log.error("rejecting pipeline result for media {}: {}", media.getId(), problem.get());
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        if (articleMediaRepository.existsVerifiedDuplicate(media.getArticleId(), image.sha256(), media.getId())) {
            fail(media, MediaFailureCode.DUPLICATE);
            return;
        }

        media.setSha256(image.sha256());
        media.setVariants(toVariants(image.variants()));
        media.setPlaceholder(image.placeholder());
        media.setAttribution(sanitizeAttribution(image.attribution()));
        media.setProcessingStatus(ProcessingStatus.READY);
        // Flushed now so a unique-index race on the verified hash surfaces from this call, not
        // from a commit the caller cannot tell apart from any other failure.
        articleMediaRepository.saveAndFlush(media);
        deleteStagingAfterCommit(media.getId());
        log.info("media {} processed: {} variant(s)", media.getId(), image.variants().size());

        mediaMetadataVersionRepository
                .findByMediaIdAndStatusOrderByVersionNumberAsc(media.getId(), MetadataVersionStatus.PENDING_REVIEW)
                .forEach(version -> eventPublisher.publishEvent(new MediaVersionAwaitingReviewEvent(version.getId())));
    }

    // Decided by the owner on 2026-11-07: the worker never deletes from the staging bucket, and
    // it has already copied the upload to originals/{mediaId}/, so once an item is READY its
    // staging object is a second copy nothing reads. Deleted only after the commit, so a result
    // that rolls back never leaves a still-PROCESSING item without its file. A failed delete
    // leaves the object behind for good (no sweep looks at READY items) -- wasted space, never a
    // missing file. FAILED items keep theirs until the failed-item sweep.
    private void deleteStagingAfterCommit(UUID mediaId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaStorage.delete(StorageBucket.STAGING, MediaUploadService.stagingKey(mediaId));
                } catch (RuntimeException ex) {
                    log.warn("could not delete the staging upload of processed media {}; it stays", mediaId, ex);
                }
            }
        });
    }

    private void applyRejected(ArticleMedia media, JobResult.@Nullable Reason reason) {
        String rawCode = reason != null ? reason.code() : null;
        MediaFailureCode code = rawCode == null
                ? MediaFailureCode.REJECTED
                : MediaFailureCode.fromPipelineCode(rawCode).orElse(MediaFailureCode.REJECTED);
        if (code == MediaFailureCode.REJECTED) {
            log.warn("pipeline rejected media {} with reason code {} (detail {})", media.getId(), truncate(rawCode),
                    reason != null ? reason.detail() : null);
        }
        fail(media, code);
    }

    private void fail(ArticleMedia media, MediaFailureCode code) {
        media.setProcessingStatus(ProcessingStatus.FAILED);
        media.setFailureCode(code.code());
        articleMediaRepository.save(media);
        log.info("media {} failed processing: {}", media.getId(), code.code());
    }

    private static Optional<String> validate(UUID mediaId, JobResult.@Nullable Image image) {
        if (image == null) {
            return Optional.of("ok result without an image payload");
        }
        if (image.sha256() == null || !SHA256_BASE64.matcher(image.sha256()).matches()) {
            return Optional.of("sha256 is not base64 of 32 bytes");
        }
        if (image.placeholder() != null && (image.placeholder().length() > MAX_PLACEHOLDER_LENGTH
                || !PLACEHOLDER.matcher(image.placeholder()).matches())) {
            return Optional.of("placeholder is not a small raster data URI");
        }
        List<JobResult.Variant> variants = image.variants();
        if (variants == null || variants.isEmpty() || variants.size() > MAX_VARIANTS) {
            return Optional.of("variant count out of range");
        }
        String prefix = pendingVariantPrefix(mediaId);
        for (JobResult.Variant variant : variants) {
            if (variant == null || variant.key() == null || variant.bytes() == null || variant.width() == null
                    || variant.height() == null || !isLabel(variant.label()) || !isLabel(variant.format())) {
                return Optional.of("variant with a missing or malformed field");
            }
            if (!variant.key().startsWith(prefix) || variant.key().length() == prefix.length()
                    || variant.key().contains("..") || variant.key().contains("//")) {
                return Optional.of("variant key outside " + prefix);
            }
            if (variant.bytes() <= 0 || variant.width() <= 0 || variant.height() <= 0) {
                return Optional.of("variant with a non-positive size");
            }
        }
        return Optional.empty();
    }

    private static boolean isLabel(@Nullable String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_LABEL_LENGTH;
    }

    // The worker's label (w320, 2k, ...) becomes the stored variant's size, the name the read
    // path has always used for it.
    private static List<MediaVariant> toVariants(List<JobResult.Variant> variants) {
        List<MediaVariant> stored = new ArrayList<>();
        for (JobResult.Variant variant : variants) {
            stored.add(new MediaVariant(variant.label(), variant.format(), variant.key(), variant.bytes(),
                    variant.width(), variant.height()));
        }
        return stored;
    }

    private static @Nullable String cleanText(@Nullable String value) {
        if (value == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                // A line break or tab becomes a space, so two words never run together.
                kept.append(' ');
            } else if (!isBidiControl(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
        });
        String cleaned = WHITESPACE_RUN.matcher(kept).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.codePointCount(0, cleaned.length()) <= MAX_ATTRIBUTION_LENGTH
                ? cleaned
                : cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_ATTRIBUTION_LENGTH)).strip();
    }

    // Embeddings and overrides (U+202A..U+202E) and isolates (U+2066..U+2069). ZWNJ, ZWJ and the
    // plain marks (LRM, RLM) stay: Persian spelling needs the first, and the marks reorder
    // nothing on their own.
    private static boolean isBidiControl(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E) || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static boolean isWebUrl(@Nullable String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static @Nullable String truncate(@Nullable String value) {
        return value == null || value.length() <= MAX_LOGGED_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_LOGGED_ERROR_LENGTH) + "...";
    }
}

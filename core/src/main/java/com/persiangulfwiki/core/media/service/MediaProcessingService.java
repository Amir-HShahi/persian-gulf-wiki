package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.event.MediaVersionAwaitingReviewEvent;
import com.persiangulfwiki.core.media.pipeline.MediaJobType;
import com.persiangulfwiki.core.media.pipeline.PipelineResult;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// Core's half of the pipeline contract on the way back: turns one worker result into the
// item's next processing state. The contract itself, from the worker's side, is
// docs/MEDIA_PIPELINE.md.
//
//   ok       -> READY, with the verified hash, variants and placeholder stored, and a moderation
//               task opened for every metadata version awaiting review. Unless the verified hash
//               is already live in the same article: then FAILED "duplicate".
//   rejected -> FAILED with the worker's reason code, if core knows it; the generic "rejected"
//               otherwise.
//   failed   -> FAILED "processing_error". The worker sends it only once it has given up
//               retrying, so core never retries it.
//
// A result is applied only to an item that is PROCESSING *and* holds the result's job_id;
// anything else (a redelivery of an applied result, a stale job, an item deleted meanwhile) is
// ignored. Delivery is at-least-once, so ignoring is the normal way a duplicate ends.
//
// The worker is another process and its output is checked, not trusted: an "ok" result whose
// payload fails validation is turned into FAILED "processing_error" instead of being stored.
// The variant-key check matters most -- those keys are later copied and deleted by core, so a
// key outside the item's own pending/{mediaId}/ prefix could otherwise aim a delete at any
// object in the media bucket.
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProcessingService {

    // The only result shape this core understands; see MediaJobPublisher.CONTRACT_VERSION for
    // the job side, which is versioned independently.
    static final int RESULT_CONTRACT_VERSION = 1;

    // Standard padded base64 of 32 bytes: 43 significant characters and one "=".
    private static final Pattern SHA256_BASE64 = Pattern.compile("^[A-Za-z0-9+/]{43}=$");
    // Rendered by the frontend as an <img src>, so restricted to raster types: no SVG, which
    // can carry script when opened on its own.
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^data:image/(jpeg|png|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$");
    private static final int MAX_PLACEHOLDER_LENGTH = 16 * 1024;
    private static final int MAX_VARIANTS = 32;
    private static final int MAX_LABEL_LENGTH = 40;
    // The longest operator-facing error text kept in a log line.
    private static final int MAX_LOGGED_ERROR_LENGTH = 500;

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final ApplicationEventPublisher eventPublisher;

    // Where the worker writes an item's variants; everything under it belongs to that item
    // alone, which is what lets the cleanup sweeps delete the prefix wholesale.
    public static String pendingVariantPrefix(UUID mediaId) {
        return "pending/" + mediaId + "/";
    }

    // Throws only for infrastructure failures (the database), which the caller retries by
    // leaving the stream entry unacknowledged -- including a unique-index race on the verified
    // hash, which the retry then resolves as a duplicate. Everything about the result itself is
    // handled here and never throws.
    @Transactional
    public void applyResult(PipelineResult result) {
        if (result.version() == null || result.version() != RESULT_CONTRACT_VERSION) {
            // Not safe to read further. Left for the stuck-processing sweep rather than failed
            // here, because nothing in an unknown shape can be trusted to name the item.
            log.error("dropping pipeline result for job {} with unsupported contract version {}", result.jobId(),
                    result.version());
            return;
        }
        Optional<UUID> mediaId = parseUuid(result.submissionId());
        Optional<UUID> jobId = parseUuid(result.jobId());
        if (mediaId.isEmpty() || jobId.isEmpty()) {
            log.error("dropping pipeline result with unreadable submission_id/job_id ({}/{})", result.submissionId(),
                    result.jobId());
            return;
        }

        ArticleMedia media = articleMediaRepository.findByIdForUpdate(mediaId.get()).orElse(null);
        if (media == null) {
            log.info("ignoring pipeline result for job {}: media {} no longer exists", jobId.get(), mediaId.get());
            return;
        }
        if (media.getProcessingStatus() != ProcessingStatus.PROCESSING
                || !jobId.get().equals(media.getProcessingJobId())) {
            log.info("ignoring pipeline result for job {}: media {} is {} awaiting job {}", jobId.get(), media.getId(),
                    media.getProcessingStatus(), media.getProcessingJobId());
            return;
        }

        String expectedType = MediaJobType.of(media.getType()).wireName();
        if (!expectedType.equals(result.type())) {
            log.error("pipeline result for media {} has type {}, expected {}", media.getId(), result.type(),
                    expectedType);
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }

        switch (result.status() == null ? "" : result.status()) {
            case PipelineResult.STATUS_OK -> applyOk(media, result.media());
            case PipelineResult.STATUS_REJECTED -> applyRejected(media, result.reason());
            case PipelineResult.STATUS_FAILED -> {
                log.warn("pipeline gave up on media {} after attempt {}: {}", media.getId(), result.attempt(),
                        truncate(result.error()));
                fail(media, MediaFailureCode.PROCESSING_ERROR);
            }
            default -> {
                log.error("pipeline result for media {} has unknown status {}", media.getId(), result.status());
                fail(media, MediaFailureCode.PROCESSING_ERROR);
            }
        }
    }

    // Items no result ever came back for: the job was lost, the worker is down, or it is
    // stuck. The item is failed so its uploader learns and its pending slot frees up; a result
    // that arrives afterwards finds it no longer PROCESSING and is ignored.
    @Transactional
    public int failStuckProcessing(Instant updatedBefore) {
        int failed = articleMediaRepository.failProcessingUpdatedBefore(updatedBefore,
                MediaFailureCode.PROCESSING_TIMEOUT.code(), Instant.now());
        if (failed > 0) {
            log.warn("failed {} media item(s) stuck in PROCESSING since before {}", failed, updatedBefore);
        }
        return failed;
    }

    private void applyOk(ArticleMedia media, PipelineResult.@Nullable Media payload) {
        Optional<String> problem = validate(media.getId(), payload);
        if (problem.isPresent()) {
            log.error("rejecting pipeline result for media {}: {}", media.getId(), problem.get());
            fail(media, MediaFailureCode.PROCESSING_ERROR);
            return;
        }
        if (articleMediaRepository.existsVerifiedDuplicate(media.getArticleId(), payload.sha256(), media.getId())) {
            fail(media, MediaFailureCode.DUPLICATE);
            return;
        }

        media.setSha256(payload.sha256());
        media.setVariants(toVariants(payload.variants()));
        media.setPlaceholder(payload.placeholder());
        media.setProcessingStatus(ProcessingStatus.READY);
        // Flushed now so a unique-index race on the verified hash surfaces from this call, not
        // from a commit the caller cannot tell apart from any other failure.
        articleMediaRepository.saveAndFlush(media);
        log.info("media {} processed: {} variant(s)", media.getId(), payload.variants().size());

        mediaMetadataVersionRepository
                .findByMediaIdAndStatusOrderByVersionNumberAsc(media.getId(), MetadataVersionStatus.PENDING_REVIEW)
                .forEach(version -> eventPublisher.publishEvent(new MediaVersionAwaitingReviewEvent(version.getId())));
    }

    private void applyRejected(ArticleMedia media, PipelineResult.@Nullable Reason reason) {
        String rawCode = reason != null ? reason.code() : null;
        MediaFailureCode code = rawCode == null
                ? MediaFailureCode.REJECTED
                : MediaFailureCode.fromPipelineCode(rawCode).orElse(MediaFailureCode.REJECTED);
        if (code == MediaFailureCode.REJECTED) {
            log.warn("pipeline rejected media {} with unknown reason code {}", media.getId(), truncate(rawCode));
        }
        fail(media, code);
    }

    private void fail(ArticleMedia media, MediaFailureCode code) {
        media.setProcessingStatus(ProcessingStatus.FAILED);
        media.setFailureCode(code.code());
        articleMediaRepository.save(media);
        log.info("media {} failed processing: {}", media.getId(), code.code());
    }

    private static Optional<String> validate(UUID mediaId, PipelineResult.@Nullable Media payload) {
        if (payload == null) {
            return Optional.of("ok result without a media payload");
        }
        if (payload.sha256() == null || !SHA256_BASE64.matcher(payload.sha256()).matches()) {
            return Optional.of("sha256 is not base64 of 32 bytes");
        }
        if (payload.placeholder() != null && (payload.placeholder().length() > MAX_PLACEHOLDER_LENGTH
                || !PLACEHOLDER.matcher(payload.placeholder()).matches())) {
            return Optional.of("placeholder is not a small raster data URI");
        }
        List<PipelineResult.Variant> variants = payload.variants();
        if (variants == null || variants.isEmpty() || variants.size() > MAX_VARIANTS) {
            return Optional.of("variant count out of range");
        }
        String prefix = pendingVariantPrefix(mediaId);
        for (PipelineResult.Variant variant : variants) {
            if (variant == null || variant.key() == null || variant.bytes() == null || variant.width() == null
                    || variant.height() == null || !isLabel(variant.size()) || !isLabel(variant.format())) {
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

    private static List<MediaVariant> toVariants(List<PipelineResult.Variant> variants) {
        List<MediaVariant> stored = new ArrayList<>();
        for (PipelineResult.Variant variant : variants) {
            stored.add(new MediaVariant(variant.size(), variant.format(), variant.key(), variant.bytes(),
                    variant.width(), variant.height()));
        }
        return stored;
    }

    private static Optional<UUID> parseUuid(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static @Nullable String truncate(@Nullable String value) {
        return value == null || value.length() <= MAX_LOGGED_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_LOGGED_ERROR_LENGTH) + "...";
    }
}

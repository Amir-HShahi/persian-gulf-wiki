package com.persiangulfwiki.core.media;

import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaUploadService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// Reclaims the gallery items that nobody will ever come back for, together with their storage
// objects, and first fails the ones whose processing never finished:
//
//   - stuck processing: still PROCESSING past the timeout. Only failed here (FAILED
//     "processing_timeout"), not deleted, so the uploader can see what happened; the failed-item
//     sweep below deletes it later.
//   - abandoned uploads: still UPLOADING past the TTL. The presigned URL expired long ago, so the
//     item can never be completed; its staging object exists only if the client PUT the file
//     and never called /complete.
//   - rejected items: REJECTED for longer than the grace period. The grace period is what lets
//     a moderator reverse a mistaken rejection before the file is gone for good.
//   - failed items: FAILED for longer than the grace period, which is how long the uploader can
//     still read the failure reason. Their objects are whatever the failure left behind: a
//     staging original the worker never got to delete, and variants the worker wrote for a
//     duplicate or after the item had already timed out.
//
// Scheduling is enabled globally by OAuth2CleanupConfig.
//
// Deliberately *not* one @Transactional bulk delete like the dev sweepers: every row owns
// objects in storage, and a transaction cannot cover those. Each item's objects are deleted
// first and its row second, each row in its own repository transaction. If a storage delete
// fails, the row is kept and the next run retries it -- the reverse order would lose the only
// pointer to an object that then lives in the bucket forever.
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCleanupJob {

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaStorage mediaStorage;
    private final MediaProcessingService mediaProcessingService;

    @Value("${app.media.abandoned-upload-ttl-hours}")
    private final long abandonedUploadTtlHours;

    @Value("${app.media.rejected-grace-days}")
    private final long rejectedGraceDays;

    @Value("${app.media.failed-grace-days}")
    private final long failedGraceDays;

    @Value("${app.media.processing-timeout-minutes}")
    private final long processingTimeoutMinutes;

    // :05 hourly -- clear of the dev sweepers (:10-:40), and hourly rather than daily because
    // an abandoned upload holds a pending-item slot and quota against its uploader until it goes.
    @Scheduled(cron = "0 5 * * * *")
    public void sweep() {
        mediaProcessingService.failStuckProcessing(Instant.now().minus(processingTimeoutMinutes, ChronoUnit.MINUTES));
        sweepAbandonedUploads(Instant.now().minus(abandonedUploadTtlHours, ChronoUnit.HOURS));
        sweepRejected(Instant.now().minus(rejectedGraceDays, ChronoUnit.DAYS));
        sweepFailed(Instant.now().minus(failedGraceDays, ChronoUnit.DAYS));
    }

    // Split from the trigger, taking the threshold, so tests can drive the boundary without
    // fabricating created_at/updated_at (both are stamped by AuditableEntity's callbacks).
    public int sweepAbandonedUploads(Instant createdBefore) {
        return deleteAll(articleMediaRepository.findByProcessingStatusAndCreatedAtBefore(
                ProcessingStatus.UPLOADING, createdBefore), "abandoned upload");
    }

    public int sweepRejected(Instant rejectedBefore) {
        return deleteAll(articleMediaRepository.findByPublicationStatusAndUpdatedAtBefore(
                PublicationStatus.REJECTED, rejectedBefore), "rejected item");
    }

    public int sweepFailed(Instant failedBefore) {
        return deleteAll(articleMediaRepository.findByProcessingStatusAndUpdatedAtBefore(
                ProcessingStatus.FAILED, failedBefore), "failed item");
    }

    private int deleteAll(List<ArticleMedia> items, String kind) {
        int deleted = 0;
        for (ArticleMedia media : items) {
            try {
                // The staging delete is unconditional: deleting a key that does not exist is a
                // no-op, and a HEAD first would only double the calls.
                mediaStorage.delete(StorageBucket.STAGING, MediaUploadService.stagingKey(media.getId()));
                // The prefix covers every variant the worker wrote, recorded on the row or not;
                // the recorded keys are deleted too in case any sits outside it.
                mediaStorage.deletePrefix(StorageBucket.MEDIA, MediaProcessingService.pendingVariantPrefix(media.getId()));
                // Normally empty for every item swept here (none was ever published), but an
                // approval whose commit failed after the copy leaves objects behind.
                mediaStorage.deletePrefix(StorageBucket.MEDIA, MediaVariantService.publicVariantPrefix(media.getId()));
                for (MediaVariant variant : media.getVariants()) {
                    mediaStorage.delete(StorageBucket.MEDIA, variant.key());
                }
                // Metadata versions, descriptions, panorama links (both directions) and
                // moderation tasks all cascade from this row (V18).
                articleMediaRepository.delete(media);
                deleted++;
            } catch (RuntimeException ex) {
                log.warn("could not sweep {} {}; will retry next run", kind, media.getId(), ex);
            }
        }
        if (deleted > 0) {
            log.info("swept {} {}(s)", deleted, kind);
        }
        return deleted;
    }
}

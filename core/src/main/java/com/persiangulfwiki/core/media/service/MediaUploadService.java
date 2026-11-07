package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleVisibilityService;
import com.persiangulfwiki.core.jobqueue.entity.JobType;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.media.dto.MediaResponse;
import com.persiangulfwiki.core.media.dto.ReserveMediaRequest;
import com.persiangulfwiki.core.media.dto.ReserveMediaResponse;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.exception.DuplicateMediaException;
import com.persiangulfwiki.core.media.exception.MediaNotAwaitingUploadException;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.exception.NotMediaUploaderException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.PresignedUpload;
import com.persiangulfwiki.core.media.storage.StorageBucket;
import com.persiangulfwiki.core.media.storage.StoredObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// The upload half of the gallery: reserve -> (client PUTs straight to storage) -> complete.
//
//   reserve  -- writes the item (UPLOADING, PENDING) and its first metadata version
//               (PENDING_REVIEW), then signs a PUT for the staging object. No storage round
//               trip: signing is local.
//   complete -- checks what actually landed in storage against what was declared. A match
//               moves the item to PROCESSING and inserts its pipeline job in the same
//               transaction; a mismatch or a missing object is FAILED, and a mismatched object
//               is deleted on the spot.
//
// No moderation task is opened here, for either the item or its first metadata version: that
// happens when the pipeline reports READY (MediaProcessingService), so a moderator is never
// asked to judge a file that has not been processed yet.
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private final ArticleRepository articleRepository;
    private final ArticleVisibilityService articleVisibilityService;
    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionService mediaMetadataVersionService;
    private final MediaUploadLimitService mediaUploadLimitService;
    private final MediaStorage mediaStorage;
    private final JobQueueService jobQueueService;
    private final MediaReadService mediaReadService;

    @Value("${app.media.upload-url-ttl-minutes}")
    private final long uploadUrlTtlMinutes;

    // Check order follows how cheap each check is and what it reveals: the article first (an
    // article the caller cannot see is a 404, never a hint that it exists), then the request's
    // own shape, then the caller's limits, then the duplicate check -- which is last because
    // it is the only one that reads other users' uploads.
    @Transactional
    public ReserveMediaResponse reserve(UUID callerUserId, boolean isCallerModerator, UUID articleId,
            ReserveMediaRequest request) {
        requireReadableArticle(articleId, callerUserId, isCallerModerator);

        String contentType = mediaUploadLimitService.requireAllowedFile(request.type(), request.contentType(),
                request.bytes());
        mediaMetadataVersionService.requireValid(callerUserId, isCallerModerator, request.type(), null,
                request.metadata());
        mediaUploadLimitService.requireWithinUploaderLimits(callerUserId, request.bytes());

        if (articleMediaRepository.existsLiveDuplicate(articleId, request.sha256())) {
            throw new DuplicateMediaException();
        }

        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(request.type())
                .uploadedBy(callerUserId)
                .declaredContentType(contentType)
                .declaredBytes(request.bytes())
                .declaredSha256(request.sha256())
                .processingStatus(ProcessingStatus.UPLOADING)
                .publicationStatus(PublicationStatus.PENDING)
                .build());
        mediaMetadataVersionService.save(media.getId(), 1, callerUserId, request.metadata());

        PresignedUpload upload = mediaStorage.presignPut(StorageBucket.STAGING, stagingKey(media.getId()),
                contentType, request.bytes(), request.sha256(), Duration.ofMinutes(uploadUrlTtlMinutes));
        log.info("reserved {} upload {} on article {} for user {}", request.type(), media.getId(), articleId,
                callerUserId);
        return new ReserveMediaResponse(media.getId(), upload.url(), upload.expiresAt(), upload.requiredHeaders());
    }

    // Guard order mirrors ModerationService.decide's reasoning: who is asking (403) before the
    // item's state (409), so a stranger learns nothing about how far someone else's upload got.
    //
    // A mismatch is not an error response: the call did its job -- it found out what was
    // uploaded -- and the item's new state (FAILED + failureCode) is the answer. That is also
    // why it must not throw: throwing would roll the FAILED status back.
    @Transactional
    public MediaResponse complete(UUID callerUserId, UUID articleId, UUID mediaId) {
        ArticleMedia media = articleMediaRepository.findByIdAndArticleId(mediaId, articleId)
                .orElseThrow(MediaNotFoundException::new);
        if (!media.getUploadedBy().equals(callerUserId)) {
            throw new NotMediaUploaderException();
        }
        if (media.getProcessingStatus() != ProcessingStatus.UPLOADING) {
            throw new MediaNotAwaitingUploadException();
        }

        String key = stagingKey(media.getId());
        Optional<StoredObject> stored = mediaStorage.head(StorageBucket.STAGING, key);
        if (stored.isEmpty()) {
            fail(media, MediaFailureCode.UPLOAD_MISSING);
        } else if (!matchesDeclaration(media, stored.get())) {
            // Deleted now rather than left to a sweep: nothing will ever read it, and it may
            // be the very file the declaration was trying to smuggle past the limits.
            mediaStorage.delete(StorageBucket.STAGING, key);
            fail(media, MediaFailureCode.UPLOAD_MISMATCH);
        } else {
            media.setProcessingStatus(ProcessingStatus.PROCESSING);
            // A VIDEO is queued nowhere: the worker has no video handler, and a job of a type it
            // cannot run is dead-lettered as failed, which would read as a broken file. So it
            // stays PROCESSING until the stuck-processing sweep fails it as processing_timeout
            // -- up to app.media.processing-timeout-minutes plus the sweep's hour, about 2 h,
            // during which it holds one of its uploader's pending slots and its bytes count
            // against their quota. The owner chose this on 2026-11-07 knowing that cost, for as
            // long as the worker has no video handler.
            Optional<JobType> jobType = MediaProcessingService.jobTypeFor(media.getType());
            if (jobType.isPresent()) {
                media.setProcessingQueueJobId(jobQueueService.enqueue(jobType.get(), jobPayload(media, key)));
            }
        }
        return mediaReadService.toResponse(articleMediaRepository.save(media), callerUserId, false);
    }

    // The worker's image/panorama payload (submission-pipeline/README.md). submission_id names
    // the item's folders in the media bucket (pending/{id}/, originals/{id}/), which is why it
    // must be the server-generated id. declared_sha256 makes the worker refuse a file whose bytes
    // hash differently (checksum_mismatch); content_type and declared_bytes are not read by the
    // worker today and cost nothing to send.
    private static Map<String, Object> jobPayload(ArticleMedia media, String stagingKey) {
        return Map.of(
                "submission_id", media.getId().toString(),
                "object_key", stagingKey,
                "declared_sha256", media.getDeclaredSha256(),
                "content_type", media.getDeclaredContentType(),
                "declared_bytes", media.getDeclaredBytes());
    }

    // The staging object is keyed by the server-generated media id alone, never by anything
    // the client supplied (file name, content type), so a caller cannot steer where it lands.
    public static String stagingKey(UUID mediaId) {
        return mediaId.toString();
    }

    // Size must always match. The checksum is compared only when the store reports one: every
    // PUT this app signs carries x-amz-checksum-sha256 and the store refuses a body that does
    // not match it, so a missing value means a store that does not record checksums, not a
    // forged file. The pipeline recomputes the hash regardless, and its value is authoritative.
    private boolean matchesDeclaration(ArticleMedia media, StoredObject stored) {
        if (stored.bytes() != media.getDeclaredBytes()) {
            return false;
        }
        if (stored.sha256() == null) {
            log.debug("storage reported no checksum for media {}; deferring to the pipeline", media.getId());
            return true;
        }
        return stored.sha256().equals(media.getDeclaredSha256());
    }

    private void fail(ArticleMedia media, MediaFailureCode failureCode) {
        media.setProcessingStatus(ProcessingStatus.FAILED);
        media.setFailureCode(failureCode.code());
        log.info("media {} failed upload verification: {}", media.getId(), failureCode);
    }

    private void requireReadableArticle(UUID articleId, UUID callerUserId, boolean isCallerModerator) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (!articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator)) {
            throw new ArticleNotFoundException();
        }
    }
}

package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.exception.MediaStorageQuotaExceededException;
import com.persiangulfwiki.core.media.exception.MediaTooLargeException;
import com.persiangulfwiki.core.media.exception.MediaUploadRateLimitedException;
import com.persiangulfwiki.core.media.exception.MetadataEditRateLimitedException;
import com.persiangulfwiki.core.media.exception.PendingMediaLimitExceededException;
import com.persiangulfwiki.core.media.exception.PendingMetadataEditLimitExceededException;
import com.persiangulfwiki.core.media.exception.UnsupportedMediaContentTypeException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

// Everything that decides whether a reservation may go ahead at all, before a row is written:
// what may be uploaded (per-type content types and sizes) and how much one account may upload
// (rate, concurrent pending items, total stored bytes). Any signed-in account can upload, so
// these limits are the only thing standing between the storage bill and a script. The same
// shape of limit guards metadata edits (rate + pending cap), since each one opens a moderation
// task and so is the moderator queue's spam vector.
//
// Every number is config (app.media.*). The content-type allowlist is not: it is the contract
// with the pipeline's decoders, and widening it means a pipeline change too.
@Service
@RequiredArgsConstructor
public class MediaUploadLimitService {

    private static final Map<MediaKind, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
            MediaKind.IMAGE, Set.of("image/jpeg", "image/png", "image/webp"),
            MediaKind.PANORAMA_360, Set.of("image/jpeg", "image/png", "image/webp"),
            MediaKind.VIDEO, Set.of("video/mp4", "video/quicktime", "video/webm"));

    private static final String RATE_KEY_PREFIX = "media:reservations:";
    private static final String EDIT_RATE_KEY_PREFIX = "media:metadata-edits:";

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final StringRedisTemplate redisTemplate;

    // Each kept under 100 MB (not MiB) while staging uploads pass through a Cloudflare Tunnel,
    // whose request-body limit on this plan is 100 MB; multipart upload lifts that later.
    @Value("${app.media.max-bytes.image}")
    private final long maxImageBytes;

    @Value("${app.media.max-bytes.panorama}")
    private final long maxPanoramaBytes;

    @Value("${app.media.max-bytes.video}")
    private final long maxVideoBytes;

    @Value("${app.media.reservations-per-window}")
    private final long reservationsPerWindow;

    @Value("${app.media.reservation-window-minutes}")
    private final long reservationWindowMinutes;

    @Value("${app.media.max-pending-per-user}")
    private final long maxPendingPerUser;

    @Value("${app.media.storage-quota-bytes-per-user}")
    private final long storageQuotaBytesPerUser;

    @Value("${app.media.metadata-edits-per-window}")
    private final long metadataEditsPerWindow;

    @Value("${app.media.metadata-edit-window-minutes}")
    private final long metadataEditWindowMinutes;

    @Value("${app.media.max-pending-edits-per-user}")
    private final long maxPendingEditsPerUser;

    // Lower-cased because MIME types are case-insensitive and the result is what gets signed
    // into the upload URL -- the client then sends back exactly this value.
    public String requireAllowedFile(MediaKind kind, String contentType, long bytes) {
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.get(kind).contains(normalized)) {
            throw new UnsupportedMediaContentTypeException();
        }
        if (bytes > maxBytes(kind)) {
            throw new MediaTooLargeException();
        }
        return normalized;
    }

    // Order: rate first, because it is the cheapest check and the one a script trips first;
    // it also counts *attempts*, so a caller probing the other two limits still spends its
    // budget doing so.
    //
    // The pending-cap and quota checks are check-then-insert and not serialized per user, so
    // a burst of concurrent reservations can overshoot either by roughly the burst size. The
    // rate limit bounds that burst, which is why these are soft limits by design rather than
    // being made exact with a per-user lock.
    public void requireWithinUploaderLimits(UUID userId, long bytes) {
        requireWithinRate(RATE_KEY_PREFIX, userId, reservationsPerWindow, reservationWindowMinutes,
                MediaUploadRateLimitedException::new);
        if (articleMediaRepository.countPendingByUploader(userId) >= maxPendingPerUser) {
            throw new PendingMediaLimitExceededException();
        }
        if (articleMediaRepository.sumStoredBytesByUploader(userId) + bytes > storageQuotaBytesPerUser) {
            throw new MediaStorageQuotaExceededException();
        }
    }

    // Same order and the same soft-limit caveat as requireWithinUploaderLimits. Moderators are
    // not subject to it; the caller decides that.
    public void requireWithinEditLimits(UUID userId) {
        requireWithinRate(EDIT_RATE_KEY_PREFIX, userId, metadataEditsPerWindow, metadataEditWindowMinutes,
                MetadataEditRateLimitedException::new);
        if (mediaMetadataVersionRepository.countPendingEditsBySubmitter(userId) >= maxPendingEditsPerUser) {
            throw new PendingMetadataEditLimitExceededException();
        }
    }

    // Fixed window in Redis rather than a count of recent rows: rows can be deleted (the
    // uploader deleting a pending item, the sweeps), and a limit that forgets deleted attempts is
    // one a script can reset at will.
    private void requireWithinRate(String keyPrefix, UUID userId, long limit, long windowMinutes,
            LongFunction<? extends RuntimeException> limitReached) {
        long windowSeconds = Duration.ofMinutes(windowMinutes).toSeconds();
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        String key = keyPrefix + userId + ":" + windowStart;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }
        if (count != null && count > limit) {
            throw limitReached.apply(windowStart + windowSeconds - now);
        }
    }

    private long maxBytes(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> maxImageBytes;
            case PANORAMA_360 -> maxPanoramaBytes;
            case VIDEO -> maxVideoBytes;
        };
    }
}

package com.persiangulfwiki.core.user.service;

import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.PresignedUpload;
import com.persiangulfwiki.core.media.storage.StorageBucket;
import com.persiangulfwiki.core.media.storage.StoredObject;
import com.persiangulfwiki.core.user.dto.ReserveAvatarRequest;
import com.persiangulfwiki.core.user.dto.ReserveAvatarResponse;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.exception.AvatarNotFoundException;
import com.persiangulfwiki.core.user.exception.AvatarTooLargeException;
import com.persiangulfwiki.core.user.exception.AvatarUploadMissingException;
import com.persiangulfwiki.core.user.exception.AvatarUploadRateLimitedException;
import com.persiangulfwiki.core.user.exception.UnsupportedAvatarContentTypeException;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Avatar upload: reserve -> (client PUTs straight to staging) -> complete. Unlike gallery media
// there is no moderation and no pipeline job: complete re-encodes the file in-process
// (AvatarImageService) and publishes the result at once, under public/avatars/{userId}/.
//
// A user has at most one outstanding reservation, held on the users row; a new reserve replaces
// it. The staging object of a replaced or abandoned one is deleted here or by AvatarCleanupJob.
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String STAGING_ROOT = "avatars/";
    private static final String PUBLIC_ROOT = "public/";
    private static final String PUBLIC_AVATAR_ROOT = PUBLIC_ROOT + "avatars/";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String RATE_KEY_PREFIX = "avatar:reservations:";
    // Enough of the output's SHA-256 that two avatars of one user never share a key.
    private static final int KEY_HASH_BYTES = 8;

    private final UserRepository userRepository;
    private final MediaStorage mediaStorage;
    private final AvatarImageService avatarImageService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.avatar.max-bytes}")
    private final long maxBytes;

    @Value("${app.avatar.upload-url-ttl-minutes}")
    private final long uploadUrlTtlMinutes;

    @Value("${app.avatar.reservations-per-window}")
    private final long reservationsPerWindow;

    @Value("${app.avatar.reservation-window-minutes}")
    private final long reservationWindowMinutes;

    @Value("${app.storage.public-base-url}")
    private final String publicBaseUrl;

    @Transactional
    public ReserveAvatarResponse reserve(UUID userId, ReserveAvatarRequest request) {
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedAvatarContentTypeException();
        }
        if (request.bytes() > maxBytes) {
            throw new AvatarTooLargeException();
        }
        requireWithinRate(userId);

        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        UUID replaced = user.getAvatarUploadId();

        UUID avatarId = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(uploadUrlTtlMinutes);
        PresignedUpload upload = mediaStorage.presignPut(StorageBucket.STAGING, stagingKey(userId, avatarId),
                contentType, request.bytes(), request.sha256(), ttl);
        user.setAvatarUploadId(avatarId);
        user.setAvatarUploadContentType(contentType);
        user.setAvatarUploadBytes(request.bytes());
        user.setAvatarUploadSha256(request.sha256());
        user.setAvatarUploadExpiresAt(upload.expiresAt());
        userRepository.save(user);

        if (replaced != null) {
            deleteAfterCommit(StorageBucket.STAGING, stagingKey(userId, replaced), userId);
        }
        return new ReserveAvatarResponse(avatarId, upload.url(), upload.expiresAt(), upload.requiredHeaders());
    }

    // Unknown, replaced and already-completed ids are all the same 404: the only id that can be
    // completed is the caller's one outstanding reservation.
    @Transactional
    public User complete(UUID userId, UUID avatarId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (!avatarId.equals(user.getAvatarUploadId())) {
            throw new AvatarNotFoundException();
        }
        String stagingKey = stagingKey(userId, avatarId);
        StoredObject stored = mediaStorage.head(StorageBucket.STAGING, stagingKey)
                .orElseThrow(AvatarUploadMissingException::new);
        if (!matchesReservation(user, stored)) {
            // Left for a corrected re-upload to the same URL to overwrite, or for the sweep.
            throw new AvatarUploadMissingException();
        }
        byte[] upload = mediaStorage.read(StorageBucket.STAGING, stagingKey)
                .orElseThrow(AvatarUploadMissingException::new);
        byte[] avatar = avatarImageService.toAvatarJpeg(upload, user.getAvatarUploadContentType());

        String publicKey = PUBLIC_AVATAR_ROOT + userId + "/" + shortHash(avatar) + ".jpg";
        mediaStorage.put(StorageBucket.MEDIA, publicKey, avatar, "image/jpeg", IMMUTABLE);

        String previousKey = user.getAvatarKey();
        user.setAvatarKey(publicKey);
        user.clearAvatarUpload();
        User saved = userRepository.save(user);

        deleteAfterCommit(StorageBucket.STAGING, stagingKey, userId);
        if (previousKey != null && !previousKey.equals(publicKey)) {
            deleteAfterCommit(StorageBucket.MEDIA, previousKey, userId);
        }
        log.info("avatar updated for user {}", userId);
        return saved;
    }

    // Each reservation in its own transaction, storage first: if the delete fails the row keeps
    // the only pointer to the object and the next run retries it.
    public int sweepAbandonedUploads(Instant expiredBefore) {
        int swept = 0;
        for (User user : userRepository.findByAvatarUploadExpiresAtBefore(expiredBefore)) {
            UUID uploadId = user.getAvatarUploadId();
            if (uploadId == null) {
                continue;
            }
            mediaStorage.delete(StorageBucket.STAGING, stagingKey(user.getId(), uploadId));
            // Conditional on the same id, so a reservation made since the read is left alone.
            swept += userRepository.clearAvatarUpload(user.getId(), uploadId);
        }
        return swept;
    }

    // The public base URL names the public/ prefix itself, so the key is appended without it.
    public Optional<String> publicUrl(@Nullable String avatarKey) {
        if (avatarKey == null || !avatarKey.startsWith(PUBLIC_AVATAR_ROOT)) {
            return Optional.empty();
        }
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return Optional.of(base + "/" + avatarKey.substring(PUBLIC_ROOT.length()));
    }

    // Keyed by server-generated ids only, so a caller cannot steer where the object lands.
    private static String stagingKey(UUID userId, UUID avatarId) {
        return STAGING_ROOT + userId + "/" + avatarId;
    }

    // Size must always match; the checksum only when the store reports one (see
    // MediaUploadService.matchesDeclaration). Either way the file is decoded and re-encoded next,
    // so a forged checksum gains nothing.
    private static boolean matchesReservation(User user, StoredObject stored) {
        if (user.getAvatarUploadBytes() == null || stored.bytes() != user.getAvatarUploadBytes()) {
            return false;
        }
        return stored.sha256() == null || stored.sha256().equals(user.getAvatarUploadSha256());
    }

    // Fixed window in Redis, the same shape as MediaUploadLimitService's: it counts attempts, so
    // replacing a reservation does not reset it.
    private void requireWithinRate(UUID userId) {
        long windowSeconds = Duration.ofMinutes(reservationWindowMinutes).toSeconds();
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        String key = RATE_KEY_PREFIX + userId + ":" + windowStart;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }
        if (count != null && count > reservationsPerWindow) {
            throw new AvatarUploadRateLimitedException(windowStart + windowSeconds - now);
        }
    }

    private void deleteAfterCommit(StorageBucket bucket, String key, UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaStorage.delete(bucket, key);
                } catch (RuntimeException ex) {
                    log.warn("could not delete an old avatar object of user {}; it stays orphaned", userId, ex);
                }
            }
        });
    }

    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, KEY_HASH_BYTES);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }
}

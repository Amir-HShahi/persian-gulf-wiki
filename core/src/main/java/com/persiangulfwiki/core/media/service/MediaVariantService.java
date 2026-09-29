package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.media.dto.MediaVariantResponse;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.exception.MediaFilesMissingException;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Where an item's processed files live in the media bucket and how a client reaches them. Each
// file is stored exactly once, and a variant's key always says where it is:
//
//   pending/{mediaId}/... -- written by the pipeline, private. Where an item's files live until
//                            it is first approved. Reached only through signed GETs, and only
//                            after the caller has been authorized for the item.
//   public/{mediaId}/...  -- where they move on first approval (publish below); the only
//                            anonymously readable prefix (minio-init). Served from
//                            STORAGE_PUBLIC_BASE_URL, which points at it (the CDN, later).
//
// Keyed by media id rather than by content hash (agreed with the owner): a hash-keyed public URL
// can never be taken back, whereas moving public/{mediaId}/ away does take an item offline when
// it is HIDDEN (a CDN in front will also need that prefix purged). The URLs are still immutable
// -- an item's files are written once and never change -- so they are served with a year-long,
// immutable Cache-Control.
//
// Until the HIDE flow exists (Phase 5) nothing moves files back, so a HIDDEN item's files stay
// under public/: signed reads of them work, but so does the public URL.
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaVariantService {

    private static final String PUBLIC_ROOT = "public/";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final MediaStorage mediaStorage;

    @Value("${app.storage.public-base-url}")
    private final String publicBaseUrl;

    @Value("${app.media.signed-get-ttl-minutes}")
    private final long signedGetTtlMinutes;

    public static String publicVariantPrefix(UUID mediaId) {
        return PUBLIC_ROOT + mediaId + "/";
    }

    // Moves every variant to its public key and returns the variants re-pointed there, for the
    // caller to store on the item. Must run inside the approving transaction, before the item is
    // marked PUBLISHED:
    //   - the copies happen now, so a missing file aborts the approval (MediaFilesMissingException,
    //     rolling it back) rather than publishing dead URLs;
    //   - the pending originals are deleted only once the approval has committed. Deleting them
    //     earlier would strand an item whose commit then failed: still PENDING, its rows pointing
    //     at pending/ keys that no longer exist, and unapprovable for the same reason.
    // A failed commit leaves copies under public/ for an unpublished item, under an unguessable
    // UUID prefix; the next approval overwrites them and the cleanup sweeps delete them with the
    // item. A failed post-commit delete leaves the pending originals behind until the item is
    // deleted -- a second copy, but never a missing one.
    public List<MediaVariant> publish(ArticleMedia media) {
        String pendingPrefix = MediaProcessingService.pendingVariantPrefix(media.getId());
        List<MediaVariant> published = new ArrayList<>();
        for (MediaVariant variant : media.getVariants()) {
            if (!isUnder(variant.key(), pendingPrefix)) {
                throw new IllegalStateException("variant key of media " + media.getId() + " is outside its pending prefix");
            }
            String publicKey = publicVariantPrefix(media.getId()) + variant.key().substring(pendingPrefix.length());
            if (!mediaStorage.copy(StorageBucket.MEDIA, variant.key(), publicKey, IMMUTABLE)) {
                throw new MediaFilesMissingException("variant " + variant.key() + " of media " + media.getId()
                        + " is missing from storage");
            }
            published.add(new MediaVariant(variant.size(), variant.format(), publicKey, variant.bytes(),
                    variant.width(), variant.height()));
        }
        deletePendingAfterCommit(media.getId(), pendingPrefix);
        log.info("moved {} variant(s) of media {} to its public prefix", published.size(), media.getId());
        return published;
    }

    // The caller must already have decided the item may be shown to whoever receives this: for
    // an item that is not public, every URL returned is a live credential for its file.
    public List<MediaVariantResponse> toResponses(ArticleMedia media) {
        boolean isPublic = media.isPubliclyVisible();
        List<MediaVariantResponse> responses = new ArrayList<>();
        for (MediaVariant variant : media.getVariants()) {
            Optional<String> url = isPublic ? publicUrl(media.getId(), variant) : Optional.of(signedUrl(variant));
            url.ifPresent(value -> responses.add(new MediaVariantResponse(variant.size(), variant.format(), value,
                    variant.bytes(), variant.width(), variant.height())));
        }
        return List.copyOf(responses);
    }

    // A public item's variants are under its public prefix (publish put them there). One that is
    // not -- a row written some other way -- is left out rather than mapped to a guessed URL.
    // publicBaseUrl names the public/ prefix itself, so the key is appended without it.
    private Optional<String> publicUrl(UUID mediaId, MediaVariant variant) {
        if (!isUnder(variant.key(), publicVariantPrefix(mediaId))) {
            return Optional.empty();
        }
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return Optional.of(base + "/" + variant.key().substring(PUBLIC_ROOT.length()));
    }

    private String signedUrl(MediaVariant variant) {
        return mediaStorage.presignGet(StorageBucket.MEDIA, variant.key(), Duration.ofMinutes(signedGetTtlMinutes));
    }

    private void deletePendingAfterCommit(UUID mediaId, String pendingPrefix) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaStorage.deletePrefix(StorageBucket.MEDIA, pendingPrefix);
                } catch (RuntimeException ex) {
                    log.warn("could not delete the pending originals of published media {}; they stay until the "
                            + "item is deleted", mediaId, ex);
                }
            }
        });
    }

    private static boolean isUnder(String key, String prefix) {
        return key.startsWith(prefix) && key.length() > prefix.length();
    }
}

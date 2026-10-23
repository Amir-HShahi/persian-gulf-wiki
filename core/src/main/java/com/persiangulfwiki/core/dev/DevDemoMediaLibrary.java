package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.DevDemoDataset.DemoAsset;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoAssetVariant;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;
import com.persiangulfwiki.core.media.storage.StoredObject;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The demo image library in the media bucket. The variants ship pre-made (imgpipe, the worker's own
// code -- see dev/demo/media/README.md) because core's job queue and the worker's no longer meet
// (submission-pipeline/CORE_CHANGES.md section 1), so nothing core enqueues is processed today.
//
// Each file is uploaded once under LIBRARY_PREFIX; a gallery item then gets server-side copies
// under public/{mediaId}/, the layout MediaVariantService.publish leaves behind and the only one
// its public URLs are built for. Copies, not shared keys, so deleting one item's prefix never takes
// another item's files with it.
@Component
@Profile("dev")
@RequiredArgsConstructor
class DevDemoMediaLibrary {

    private static final String CONTENT_TYPE = "image/webp";
    private static final String FORMAT = "webp";
    // Same value MediaVariantService.publish serves public variants with: a variant's key names
    // one item's file forever.
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final MediaStorage mediaStorage;

    // Idempotent: a file already there at the right size is not sent again, so a reseed costs a
    // HEAD per file rather than re-uploading the library.
    void upload(List<DemoAsset> assets) {
        for (DemoAsset asset : assets) {
            for (DemoAssetVariant variant : asset.variants()) {
                String key = libraryKey(asset, variant);
                Optional<StoredObject> existing = mediaStorage.head(StorageBucket.MEDIA, key);
                if (existing.isEmpty() || existing.get().bytes() != variant.bytes()) {
                    mediaStorage.put(StorageBucket.MEDIA, key, read(DevDemoDataset.assetPath(asset, variant)),
                            CONTENT_TYPE);
                }
            }
        }
    }

    List<MediaVariant> copyTo(DemoAsset asset, UUID mediaId) {
        String prefix = MediaVariantService.publicVariantPrefix(mediaId);
        List<MediaVariant> variants = new ArrayList<>();
        for (DemoAssetVariant variant : asset.variants()) {
            String key = prefix + variant.file();
            if (!mediaStorage.copy(StorageBucket.MEDIA, libraryKey(asset, variant), key, IMMUTABLE)) {
                throw new IllegalStateException("demo library file missing: " + libraryKey(asset, variant));
            }
            variants.add(new MediaVariant(variant.size(), FORMAT, key, variant.bytes(), variant.width(),
                    variant.height()));
        }
        return variants;
    }

    void deleteItemFiles(UUID mediaId) {
        mediaStorage.deletePrefix(StorageBucket.MEDIA, MediaVariantService.publicVariantPrefix(mediaId));
        // Never written by the seeder, but a demo item someone later re-processed or hid could
        // have files here too.
        mediaStorage.deletePrefix(StorageBucket.MEDIA, MediaProcessingService.pendingVariantPrefix(mediaId));
    }

    void deleteLibrary() {
        mediaStorage.deletePrefix(StorageBucket.MEDIA, DevDemoContent.LIBRARY_PREFIX);
    }

    private static String libraryKey(DemoAsset asset, DemoAssetVariant variant) {
        return DevDemoContent.LIBRARY_PREFIX + asset.key() + "/" + variant.file();
    }

    private static byte[] read(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read demo library file " + classpathLocation, ex);
        }
    }
}

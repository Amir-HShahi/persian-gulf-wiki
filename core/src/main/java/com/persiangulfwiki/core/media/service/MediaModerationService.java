package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.exception.MediaAlreadyRejectedException;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.exception.MediaNotReadyForReviewException;
import com.persiangulfwiki.core.media.exception.MetadataVersionSupersededException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

// The media package's half of a moderation decision -- the counterpart of
// ArticleRevisionService.applyModerationOutcome. ModerationService owns the task and the
// decision record and calls in here for what the decision means for the item. Takes a
// MetadataVersionStatus rather than moderation's Decision so this package never depends on
// moderation (which already depends on it).
//
// "First approval" means the item has no current metadata version yet, which is exactly the
// never-approved case. What an outcome does depends on it:
//
//   APPROVED, first  -> the file and its metadata are approved together: variants moved to
//                       their public keys (MediaVariantService.publish), item PUBLISHED,
//                       publishedAt stamped, current version set. Refused unless the file is
//                       READY, so nothing unprocessed is ever published.
//   APPROVED, later  -> current version swapped, like currentRevisionId on a translation. The
//                       publication status is left alone (a HIDDEN item stays hidden).
//   REJECTED, v1 of a never-approved item
//                    -> the item is REJECTED as a whole; the rejected-item sweep deletes it
//                       after its grace period.
//   REJECTED, other  -> nothing public changes.
//
// Several versions of one item can be pending at once, so two rules keep the outcome independent
// of the order moderators happen to work in:
//   - The current version only moves forward. Approving vN closes every *older* pending version
//     (they were proposed against a version that is now out of date; newer ones stay pending,
//     as edits on top of it), and approving a version older than the current one is refused
//     (MetadataVersionSupersededException).
//   - Rejecting the item (its v1, never approved) closes every other pending version too, and
//     nothing of a REJECTED item can be approved afterwards (MediaAlreadyRejectedException).
//     Without both, approving a v2 left pending after v1's rejection would take the
//     first-approval branch and publish a rejected item.
// "Closed" means REJECTED -- there is no separate SUPERSEDED status -- and the ids are returned
// so ModerationService can close their moderation tasks in the same transaction.
//
// Everything runs under the item's row lock (the one MediaMetadataVersionService and
// MediaProcessingService take), and the version's own status is re-read under it: another
// approval may have closed this version after the caller checked that it was pending.
@Service
@RequiredArgsConstructor
public class MediaModerationService {

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final MediaVariantService mediaVariantService;

    @Transactional(readOnly = true)
    public MetadataVersionStatus getVersionStatus(UUID metadataVersionId) {
        return getVersion(metadataVersionId).getStatus();
    }

    // outcome must be APPROVED or REJECTED; there is no third media decision. Returns the ids of
    // the other versions this outcome closed, oldest first.
    @Transactional
    public List<UUID> applyModerationOutcome(UUID metadataVersionId, MetadataVersionStatus outcome) {
        MediaMetadataVersion version = getVersion(metadataVersionId);
        ArticleMedia media = articleMediaRepository.findByIdForUpdate(version.getMediaId())
                .orElseThrow(MediaNotFoundException::new);
        if (!mediaMetadataVersionRepository.existsByIdAndStatus(version.getId(), MetadataVersionStatus.PENDING_REVIEW)) {
            throw new MetadataVersionSupersededException();
        }

        List<MediaMetadataVersion> closed = List.of();
        if (outcome == MetadataVersionStatus.APPROVED) {
            if (media.getPublicationStatus() == PublicationStatus.REJECTED) {
                throw new MediaAlreadyRejectedException();
            }
            if (media.getCurrentMetadataVersionId() == null) {
                if (media.getProcessingStatus() != ProcessingStatus.READY) {
                    throw new MediaNotReadyForReviewException();
                }
                media.setVariants(mediaVariantService.publish(media));
                media.setPublicationStatus(PublicationStatus.PUBLISHED);
                media.setPublishedAt(Instant.now());
            } else if (version.getVersionNumber() < getVersion(media.getCurrentMetadataVersionId()).getVersionNumber()) {
                throw new MetadataVersionSupersededException();
            }
            media.setCurrentMetadataVersionId(version.getId());
            closed = otherPendingVersions(media, other -> other.getVersionNumber() < version.getVersionNumber());
        } else if (media.getCurrentMetadataVersionId() == null && version.getVersionNumber() == 1) {
            media.setPublicationStatus(PublicationStatus.REJECTED);
            closed = otherPendingVersions(media, other -> !other.getId().equals(version.getId()));
        }

        version.setStatus(outcome);
        mediaMetadataVersionRepository.save(version);
        closed.forEach(other -> other.setStatus(MetadataVersionStatus.REJECTED));
        mediaMetadataVersionRepository.saveAll(closed);
        articleMediaRepository.save(media);
        return closed.stream().map(MediaMetadataVersion::getId).toList();
    }

    private List<MediaMetadataVersion> otherPendingVersions(ArticleMedia media,
            Predicate<MediaMetadataVersion> isClosed) {
        return mediaMetadataVersionRepository
                .findByMediaIdAndStatusOrderByVersionNumberAsc(media.getId(), MetadataVersionStatus.PENDING_REVIEW)
                .stream()
                .filter(isClosed)
                .toList();
    }

    private MediaMetadataVersion getVersion(UUID metadataVersionId) {
        return mediaMetadataVersionRepository.findById(metadataVersionId).orElseThrow(MediaNotFoundException::new);
    }
}

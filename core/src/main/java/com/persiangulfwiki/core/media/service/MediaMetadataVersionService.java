package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleVisibilityService;
import com.persiangulfwiki.core.media.dto.GeoPointRequest;
import com.persiangulfwiki.core.media.dto.MediaMetadataRequest;
import com.persiangulfwiki.core.media.dto.MediaMetadataVersionResponse;
import com.persiangulfwiki.core.media.dto.PanoramaLinkRequest;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaDescription;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.PanoramaLink;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.event.MediaVersionAwaitingReviewEvent;
import com.persiangulfwiki.core.media.exception.IncompleteMediaMetadataException;
import com.persiangulfwiki.core.media.exception.MediaNotEditableException;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.exception.NotMediaUploaderException;
import com.persiangulfwiki.core.media.exception.PanoramaLinkTargetNotFoundException;
import com.persiangulfwiki.core.media.exception.PanoramaLinkTargetNotPanoramaException;
import com.persiangulfwiki.core.media.exception.PanoramaLinksNotSupportedException;
import com.persiangulfwiki.core.media.exception.SelfPanoramaLinkException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaDescriptionRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Writes metadata versions -- the one sent with an upload (version 1, via MediaUploadService)
// and every later edit -- and reads an item's version history.
//
// A version is always a complete replacement: every field, caption and hotspot it has is what
// the request carried, and nothing is copied forward from the current version. A version also
// starts with nothing attached to it beyond what was proposed, which is what lets a future expert
// verification attach to exactly one version and stay true of it.
//
// Who may propose an edit: the uploader and moderators. Which items accept one: those whose file
// arrived and can still be shown -- PENDING while PROCESSING or READY, or PUBLISHED. An edit
// never touches what the public sees; it waits for a moderator (MediaModerationService says what
// approving or rejecting it does).
//
// A hotspot's target must be a PANORAMA_360 the caller can see: public on an article they can
// read, or one they uploaded themselves (moderators: any). A target they cannot see is the same
// 404 as one that does not exist, so the field cannot be used to probe for unpublished items.
// Links are one-way and may cross articles. A target hidden or rejected later keeps its links;
// the read path filters them.
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMetadataVersionService {

    // SRID must be on every Point: JTS defaults to 0, and PostGIS refuses to store an SRID-0
    // point in a geography(Point, 4326) column rather than coercing it.
    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    private final ArticleRepository articleRepository;
    private final ArticleVisibilityService articleVisibilityService;
    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final MediaDescriptionRepository mediaDescriptionRepository;
    private final PanoramaLinkRepository panoramaLinkRepository;
    private final MediaUploadLimitService mediaUploadLimitService;
    private final MediaReadService mediaReadService;
    private final ApplicationEventPublisher eventPublisher;

    // The item's row lock is taken first and held to commit. It is what makes max + 1 safe (two
    // concurrent edits queue on it instead of racing into uq_media_metadata_versions_media_number),
    // and it serializes this against the pipeline result turning the item READY
    // (MediaProcessingService takes the same lock), so exactly one of the two opens this
    // version's moderation task: this method if the item is already READY, the READY transition
    // otherwise.
    //
    // Guard order, same reasoning as reserve and complete: who is asking (404/403) before the
    // item's state (409) before the request's own content (400/404) before the caller's limits
    // (429/409), so a stranger learns nothing about the item and a malformed request spends none
    // of the caller's budget.
    @Transactional
    public MediaMetadataVersionResponse propose(UUID callerUserId, boolean isCallerModerator, UUID articleId,
            UUID mediaId, MediaMetadataRequest request) {
        ArticleMedia media = articleMediaRepository.findByIdForUpdate(mediaId)
                .filter(candidate -> candidate.getArticleId().equals(articleId))
                .orElseThrow(MediaNotFoundException::new);
        requireEditor(media, callerUserId, isCallerModerator);
        if (!isEditable(media)) {
            throw new MediaNotEditableException();
        }
        requireValid(callerUserId, isCallerModerator, media.getType(), media.getId(), request);
        if (!isCallerModerator) {
            mediaUploadLimitService.requireWithinEditLimits(callerUserId);
        }

        int versionNumber = mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(mediaId)
                .map(newest -> newest.getVersionNumber() + 1)
                .orElse(1);
        MediaMetadataVersion version = save(mediaId, versionNumber, callerUserId, request);
        if (media.getProcessingStatus() == ProcessingStatus.READY) {
            eventPublisher.publishEvent(new MediaVersionAwaitingReviewEvent(version.getId()));
        }
        log.info("user {} proposed metadata version {} of media {}", callerUserId, versionNumber, mediaId);
        return mediaReadService.toVersionResponses(List.of(version), media.getType(), callerUserId, isCallerModerator)
                .getFirst();
    }

    // Oldest first. The uploader and moderators see every version; anyone else sees only the
    // approved ones, and only of an item they could read on its own -- otherwise the same 404 as
    // the single-item read.
    @Transactional(readOnly = true)
    public List<MediaMetadataVersionResponse> list(@Nullable UUID callerUserId, boolean isCallerModerator,
            UUID articleId, UUID mediaId) {
        ArticleMedia media = articleMediaRepository.findByIdAndArticleId(mediaId, articleId)
                .orElseThrow(MediaNotFoundException::new);
        boolean isPrivileged = isCallerModerator || media.getUploadedBy().equals(callerUserId);
        if (!isPrivileged && !(media.isPubliclyVisible() && isArticleReadable(articleId, callerUserId, false))) {
            throw new MediaNotFoundException();
        }
        List<MediaMetadataVersion> versions = mediaMetadataVersionRepository.findByMediaIdOrderByVersionNumberAsc(mediaId)
                .stream()
                .filter(version -> isPrivileged || version.getStatus() == MetadataVersionStatus.APPROVED)
                .toList();
        return mediaReadService.toVersionResponses(versions, media.getType(), callerUserId, isCallerModerator);
    }

    // Everything about a proposed version that can be checked before it is written. sourceMediaId
    // is null for an upload's first version, whose item does not exist yet (and so cannot be
    // linked to itself).
    public void requireValid(UUID callerUserId, boolean isCallerModerator, MediaKind sourceType,
            @Nullable UUID sourceMediaId, @Nullable MediaMetadataRequest metadata) {
        if (metadata == null) {
            return;
        }
        if ((metadata.shotAtOffset() != null && metadata.shotAt() == null)
                || (metadata.headingRef() != null && metadata.headingDeg() == null)) {
            throw new IncompleteMediaMetadataException();
        }
        requireValidLinks(callerUserId, isCallerModerator, sourceType, sourceMediaId, links(metadata));
    }

    // The caller must have run requireValid on the same request first.
    public MediaMetadataVersion save(UUID mediaId, int versionNumber, UUID submittedBy,
            @Nullable MediaMetadataRequest metadata) {
        MediaMetadataVersion.MediaMetadataVersionBuilder version = MediaMetadataVersion.builder()
                .mediaId(mediaId)
                .versionNumber(versionNumber)
                .submittedBy(submittedBy)
                .status(MetadataVersionStatus.PENDING_REVIEW);
        if (metadata != null) {
            version.shotAt(metadata.shotAt())
                    .shotAtOffset(metadata.shotAtOffset())
                    .location(toPoint(metadata.location()))
                    .altitudeM(metadata.altitudeM())
                    .headingDeg(metadata.headingDeg())
                    .headingRef(metadata.headingRef());
        }
        MediaMetadataVersion saved = mediaMetadataVersionRepository.save(version.build());

        Map<String, String> descriptions = metadata != null && metadata.descriptions() != null
                ? metadata.descriptions()
                : Map.of();
        descriptions.forEach((language, text) -> mediaDescriptionRepository.save(MediaDescription.builder()
                .metadataVersionId(saved.getId())
                .language(language)
                .text(text)
                .build()));
        for (PanoramaLinkRequest link : links(metadata)) {
            panoramaLinkRepository.save(PanoramaLink.builder()
                    .metadataVersionId(saved.getId())
                    .toMediaId(link.toMediaId())
                    .yawDeg(link.yawDeg())
                    .pitchDeg(link.pitchDeg())
                    .label(link.label())
                    .build());
        }
        return saved;
    }

    // Stranger on a public item: 403, since the item's existence is no secret. On anything else,
    // the 404 of an item that does not exist.
    private void requireEditor(ArticleMedia media, UUID callerUserId, boolean isCallerModerator) {
        if (isCallerModerator || media.getUploadedBy().equals(callerUserId)) {
            return;
        }
        if (media.isPubliclyVisible() && isArticleReadable(media.getArticleId(), callerUserId, false)) {
            throw new NotMediaUploaderException();
        }
        throw new MediaNotFoundException();
    }

    // UPLOADING is excluded because nothing has arrived yet and the item may well be abandoned;
    // FAILED, REJECTED and HIDDEN because none of them can ever show the edit. (HIDDEN is Phase
    // 5's report flow; an item hidden there must be restored before it is edited.)
    private static boolean isEditable(ArticleMedia media) {
        return switch (media.getPublicationStatus()) {
            case PENDING -> media.getProcessingStatus() == ProcessingStatus.PROCESSING
                    || media.getProcessingStatus() == ProcessingStatus.READY;
            case PUBLISHED -> media.getProcessingStatus() == ProcessingStatus.READY;
            case REJECTED, HIDDEN -> false;
        };
    }

    // The at-most-20 rule is on the request itself (MediaMetadataRequest.links). Checked in this
    // order so the answer never depends on what the caller may not see: the request's own shape
    // first, then existence and visibility (404) for every target, and only then the targets'
    // type (400).
    private void requireValidLinks(UUID callerUserId, boolean isCallerModerator, MediaKind sourceType,
            @Nullable UUID sourceMediaId, List<PanoramaLinkRequest> links) {
        if (links.isEmpty()) {
            return;
        }
        if (sourceType != MediaKind.PANORAMA_360) {
            throw new PanoramaLinksNotSupportedException();
        }
        Set<UUID> targetIds = new LinkedHashSet<>();
        for (PanoramaLinkRequest link : links) {
            if (link.toMediaId().equals(sourceMediaId)) {
                throw new SelfPanoramaLinkException();
            }
            targetIds.add(link.toMediaId());
        }

        Map<UUID, ArticleMedia> targets = articleMediaRepository.findAllById(targetIds).stream()
                .collect(Collectors.toMap(ArticleMedia::getId, Function.identity()));
        Map<UUID, Boolean> readableArticles = new HashMap<>();
        for (UUID targetId : targetIds) {
            ArticleMedia target = targets.get(targetId);
            if (target == null || !isLinkable(target, callerUserId, isCallerModerator, readableArticles)) {
                throw new PanoramaLinkTargetNotFoundException();
            }
        }
        if (targets.values().stream().anyMatch(target -> target.getType() != MediaKind.PANORAMA_360)) {
            throw new PanoramaLinkTargetNotPanoramaException();
        }
    }

    private boolean isLinkable(ArticleMedia target, UUID callerUserId, boolean isCallerModerator,
            Map<UUID, Boolean> readableArticles) {
        if (isCallerModerator || target.getUploadedBy().equals(callerUserId)) {
            return true;
        }
        return target.isPubliclyVisible() && readableArticles.computeIfAbsent(target.getArticleId(),
                articleId -> isArticleReadable(articleId, callerUserId, false));
    }

    private boolean isArticleReadable(UUID articleId, @Nullable UUID callerUserId, boolean isCallerModerator) {
        return articleRepository.findById(articleId)
                .map(article -> articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator))
                .orElse(false);
    }

    private static List<PanoramaLinkRequest> links(@Nullable MediaMetadataRequest metadata) {
        return metadata != null && metadata.links() != null ? metadata.links() : List.of();
    }

    private static @Nullable Point toPoint(@Nullable GeoPointRequest location) {
        if (location == null) {
            return null;
        }
        // JTS coordinates are (x, y) = (longitude, latitude) -- the reverse of how they are
        // usually spoken, and the classic source of points landing in the wrong hemisphere.
        return WGS84.createPoint(new Coordinate(location.longitude(), location.latitude()));
    }
}

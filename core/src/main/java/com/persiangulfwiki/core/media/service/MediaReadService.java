package com.persiangulfwiki.core.media.service;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleVisibilityService;
import com.persiangulfwiki.core.media.dto.GeoPointResponse;
import com.persiangulfwiki.core.media.dto.MediaMetadataResponse;
import com.persiangulfwiki.core.media.dto.MediaMetadataVersionResponse;
import com.persiangulfwiki.core.media.dto.MediaResponse;
import com.persiangulfwiki.core.media.dto.MediaReviewResponse;
import com.persiangulfwiki.core.media.dto.MediaVariantResponse;
import com.persiangulfwiki.core.media.dto.PanoramaLinkResponse;
import com.persiangulfwiki.core.media.dto.PanoramaLinkTargetResponse;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaDescription;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.PanoramaLink;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaDescriptionRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Everything that turns gallery rows into MediaResponse, for every caller: the public gallery,
// the single-item read (the uploader's status poll), the upload confirmation and the moderation
// review.
//
// Who may see what is decided here, before any URL is built -- a signed URL is a credential, so
// signing one for a caller who then gets a 404 would already be a leak:
//   - the public gallery lists only public items (READY + PUBLISHED) of an article the caller
//     can see, and the result is the same whoever asks;
//   - a single item is readable by its uploader and moderators at any stage, by anyone else only
//     once public and only on an article they can see; everyone else gets the 404 of an item
//     that does not exist.
//
// A panorama link is shown only when its target is public *and* on an article the caller can
// see; anything else is dropped at read time, never deleted (V18), so it reappears once the
// target is approved. Two views widen that, neither of them the gallery itself:
//   - a single metadata version (a proposed edit's response, the version history) also shows
//     links to the caller's *own* uploads, since those were linkable when proposed;
//   - the moderation review shows every link, since a moderator approving a version approves
//     all of its links -- and so does the version history for a moderator, for the same reason.
// A target shown only by one of those widenings carries no preview: building one would mean
// signing its URLs, and the view is about the link, not the target.
//
// Page assembly is batched -- one query each for versions, captions, links and link targets per
// page, not per item.
@Service
@RequiredArgsConstructor
public class MediaReadService {

    private final ArticleRepository articleRepository;
    private final ArticleVisibilityService articleVisibilityService;
    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final MediaDescriptionRepository mediaDescriptionRepository;
    private final PanoramaLinkRepository panoramaLinkRepository;
    private final MediaVariantService mediaVariantService;
    private final MessageSource messageSource;

    // Who is asking, as far as the link-target article check is concerned.
    private record Caller(@Nullable UUID userId, boolean isModerator) {
    }

    // Which link targets a view shows beyond the public ones -- see the class comment.
    private enum TargetScope {
        PUBLIC,
        OWN_OR_PUBLIC,
        ALL
    }

    @Transactional(readOnly = true)
    public List<MediaResponse> listPublic(@Nullable UUID callerUserId, boolean isCallerModerator, UUID articleId,
            @Nullable String language, Pageable pageable) {
        Article article = articleRepository.findById(articleId).orElseThrow(ArticleNotFoundException::new);
        if (!articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator)) {
            throw new ArticleNotFoundException();
        }
        List<ArticleMedia> items = articleMediaRepository.findPublicByArticleId(articleId, pageable);
        return toResponses(items, language, new Caller(callerUserId, isCallerModerator));
    }

    @Transactional(readOnly = true)
    public MediaResponse get(@Nullable UUID callerUserId, boolean isCallerModerator, UUID articleId, UUID mediaId,
            @Nullable String language) {
        ArticleMedia media = articleMediaRepository.findByIdAndArticleId(mediaId, articleId)
                .orElseThrow(MediaNotFoundException::new);
        boolean isPrivileged = isCallerModerator || media.getUploadedBy().equals(callerUserId);
        if (!isPrivileged && !(media.isPubliclyVisible() && isArticleReadable(articleId, callerUserId, false))) {
            throw new MediaNotFoundException();
        }
        return toResponse(media, language, new Caller(callerUserId, isCallerModerator));
    }

    // For callers that have already established the right to see the item themselves (its
    // uploader confirming the upload).
    @Transactional(readOnly = true)
    public MediaResponse toResponse(ArticleMedia media, @Nullable UUID callerUserId, boolean isCallerModerator) {
        return toResponse(media, null, new Caller(callerUserId, isCallerModerator));
    }

    // Moderators only; the caller (ModerationService, behind the moderator-only route) is the
    // authorization check.
    @Transactional(readOnly = true)
    public MediaReviewResponse getForReview(UUID metadataVersionId) {
        MediaMetadataVersion version = mediaMetadataVersionRepository.findById(metadataVersionId)
                .orElseThrow(MediaNotFoundException::new);
        ArticleMedia media = articleMediaRepository.findById(version.getMediaId())
                .orElseThrow(MediaNotFoundException::new);
        Caller moderator = new Caller(null, true);

        Map<String, String> descriptions = mediaDescriptionRepository.findByMetadataVersionId(version.getId()).stream()
                .collect(Collectors.toUnmodifiableMap(MediaDescription::getLanguage, MediaDescription::getText));
        List<PanoramaLinkResponse> links = media.getType() == MediaKind.PANORAMA_360
                ? toLinkResponses(panoramaLinkRepository.findByMetadataVersionId(version.getId()), moderator,
                        TargetScope.ALL)
                : List.of();
        MediaMetadataVersionResponse current = media.getCurrentMetadataVersionId() == null
                ? null
                : mediaMetadataVersionRepository.findById(media.getCurrentMetadataVersionId())
                        .map(currentVersion -> toVersionResponses(List.of(currentVersion), media.getType(), moderator)
                                .getFirst())
                        .orElse(null);

        return new MediaReviewResponse(toResponse(media, null, moderator), toMetadata(version), version.getStatus(),
                version.getSubmittedBy(), version.getCreatedAt(), descriptions, links, current);
    }

    // Full versions (every caption, every link the caller may see) for a caller who has already
    // established the right to see them -- MediaMetadataVersionService decides which versions.
    // Links to the caller's own uploads are shown too, and a moderator sees every link.
    @Transactional(readOnly = true)
    public List<MediaMetadataVersionResponse> toVersionResponses(List<MediaMetadataVersion> versions, MediaKind type,
            @Nullable UUID callerUserId, boolean isCallerModerator) {
        return toVersionResponses(versions, type, new Caller(callerUserId, isCallerModerator));
    }

    private List<MediaMetadataVersionResponse> toVersionResponses(List<MediaMetadataVersion> versions, MediaKind type,
            Caller caller) {
        if (versions.isEmpty()) {
            return List.of();
        }
        List<UUID> versionIds = versions.stream().map(MediaMetadataVersion::getId).toList();
        Map<UUID, Map<String, String>> descriptionsByVersion = mediaDescriptionRepository
                .findByMetadataVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(MediaDescription::getMetadataVersionId,
                        Collectors.toUnmodifiableMap(MediaDescription::getLanguage, MediaDescription::getText)));
        Map<UUID, List<PanoramaLink>> linksByVersion = type == MediaKind.PANORAMA_360
                ? panoramaLinkRepository.findByMetadataVersionIdIn(versionIds).stream()
                        .collect(Collectors.groupingBy(PanoramaLink::getMetadataVersionId))
                : Map.of();
        Map<UUID, PanoramaLinkTargetResponse> targets = resolveTargets(
                linksByVersion.values().stream().flatMap(List::stream).toList(), caller,
                caller.isModerator() ? TargetScope.ALL : TargetScope.OWN_OR_PUBLIC);

        return versions.stream()
                .map(version -> new MediaMetadataVersionResponse(toMetadata(version), version.getStatus(),
                        version.getSubmittedBy(), version.getCreatedAt(),
                        descriptionsByVersion.getOrDefault(version.getId(), Map.of()),
                        toLinkResponses(linksByVersion.getOrDefault(version.getId(), List.of()), targets)))
                .toList();
    }

    private MediaResponse toResponse(ArticleMedia media, @Nullable String language, Caller caller) {
        return toResponses(List.of(media), language, caller).getFirst();
    }

    private List<MediaResponse> toResponses(List<ArticleMedia> items, @Nullable String language, Caller caller) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> shownVersionIdByMedia = new HashMap<>();
        for (ArticleMedia media : items) {
            shownVersionId(media).ifPresent(versionId -> shownVersionIdByMedia.put(media.getId(), versionId));
        }
        Collection<UUID> versionIds = shownVersionIdByMedia.values();

        Map<UUID, MediaMetadataVersion> versions = byId(mediaMetadataVersionRepository.findAllById(versionIds),
                MediaMetadataVersion::getId);
        Map<UUID, String> captions = language == null
                ? Map.of()
                : mediaDescriptionRepository.findByMetadataVersionIdInAndLanguage(versionIds, language).stream()
                        .collect(Collectors.toMap(MediaDescription::getMetadataVersionId, MediaDescription::getText));
        Map<UUID, List<PanoramaLink>> linksByVersion = panoramaLinkRepository.findByMetadataVersionIdIn(versionIds)
                .stream()
                .collect(Collectors.groupingBy(PanoramaLink::getMetadataVersionId));
        Map<UUID, PanoramaLinkTargetResponse> targets = resolveTargets(
                linksByVersion.values().stream().flatMap(List::stream).toList(), caller, TargetScope.PUBLIC);

        return items.stream().map(media -> {
            UUID versionId = shownVersionIdByMedia.get(media.getId());
            MediaMetadataVersion version = versionId != null ? versions.get(versionId) : null;
            List<PanoramaLinkResponse> links = media.getType() == MediaKind.PANORAMA_360 && versionId != null
                    ? toLinkResponses(linksByVersion.getOrDefault(versionId, List.of()), targets)
                    : List.of();
            return new MediaResponse(media.getId(), media.getArticleId(), media.getType(),
                    media.getProcessingStatus(), media.getPublicationStatus(), media.getFailureCode(),
                    failureMessage(media.getFailureCode()), media.getPlaceholder(),
                    mediaVariantService.toResponses(media), version != null ? toMetadata(version) : null,
                    versionId != null ? captions.get(versionId) : null, links, media.getPublishedAt(),
                    media.getCreatedAt(), media.getUpdatedAt());
        }).toList();
    }

    // The approved version once there is one. Before that -- which only the uploader and
    // moderators ever see -- the newest one, i.e. what was sent with the upload.
    private Optional<UUID> shownVersionId(ArticleMedia media) {
        if (media.getCurrentMetadataVersionId() != null) {
            return Optional.of(media.getCurrentMetadataVersionId());
        }
        return mediaMetadataVersionRepository.findFirstByMediaIdOrderByVersionNumberDesc(media.getId())
                .map(MediaMetadataVersion::getId);
    }

    private List<PanoramaLinkResponse> toLinkResponses(List<PanoramaLink> links, Caller caller, TargetScope scope) {
        return toLinkResponses(links, resolveTargets(links, caller, scope));
    }

    // A link whose target did not resolve (not public, not readable, or gone) is dropped here.
    private static List<PanoramaLinkResponse> toLinkResponses(List<PanoramaLink> links,
            Map<UUID, PanoramaLinkTargetResponse> targets) {
        return links.stream()
                .filter(link -> targets.containsKey(link.getToMediaId()))
                .sorted(Comparator.comparing(PanoramaLink::getYawDeg).thenComparing(PanoramaLink::getId))
                .map(link -> new PanoramaLinkResponse(link.getId(), link.getYawDeg(), link.getPitchDeg(),
                        link.getLabel(), targets.get(link.getToMediaId())))
                .toList();
    }

    // Target id -> what the hotspot shows of it, for every target this caller may see in this
    // scope. Only a public target on a readable article gets a preview.
    private Map<UUID, PanoramaLinkTargetResponse> resolveTargets(List<PanoramaLink> links, Caller caller,
            TargetScope scope) {
        Set<UUID> targetIds = links.stream().map(PanoramaLink::getToMediaId).collect(Collectors.toSet());
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        List<ArticleMedia> targets = articleMediaRepository.findAllById(targetIds);
        Map<UUID, Boolean> readableArticles = new HashMap<>();
        Map<UUID, PanoramaLinkTargetResponse> resolved = new HashMap<>();
        for (ArticleMedia target : targets) {
            boolean isVisible = target.isPubliclyVisible()
                    && readableArticles.computeIfAbsent(target.getArticleId(),
                            articleId -> isArticleReadable(articleId, caller.userId(), caller.isModerator()));
            if (isVisible) {
                resolved.put(target.getId(), new PanoramaLinkTargetResponse(target.getId(), target.getArticleId(),
                        target.getType(), target.getPlaceholder(), smallestVariant(target)));
            } else if (scope == TargetScope.ALL
                    || (scope == TargetScope.OWN_OR_PUBLIC && target.getUploadedBy().equals(caller.userId()))) {
                resolved.put(target.getId(), new PanoramaLinkTargetResponse(target.getId(), target.getArticleId(),
                        target.getType(), null, null));
            }
        }
        return resolved;
    }

    // By pixel area, then by size in bytes. Only ever called for a public target, so the URL
    // is a public one.
    private @Nullable MediaVariantResponse smallestVariant(ArticleMedia target) {
        return mediaVariantService.toResponses(target).stream()
                .min(Comparator.comparingLong((MediaVariantResponse variant) -> (long) variant.width() * variant.height())
                        .thenComparingLong(MediaVariantResponse::bytes))
                .orElse(null);
    }

    private boolean isArticleReadable(UUID articleId, @Nullable UUID callerUserId, boolean isCallerModerator) {
        return articleRepository.findById(articleId)
                .map(article -> articleVisibilityService.isArticleReadableBy(article, callerUserId, isCallerModerator))
                .orElse(false);
    }

    private static MediaMetadataResponse toMetadata(MediaMetadataVersion version) {
        // JTS stores (x, y) = (longitude, latitude); see MediaUploadService.toPoint.
        GeoPointResponse location = version.getLocation() != null
                ? new GeoPointResponse(version.getLocation().getY(), version.getLocation().getX())
                : null;
        return new MediaMetadataResponse(version.getId(), version.getVersionNumber(), version.getShotAt(),
                version.getShotAtOffset(), location, version.getAltitudeM(), version.getHeadingDeg(),
                version.getHeadingRef());
    }

    // Resolved here rather than left to the frontend so every client gets the same wording in
    // all three locales. A stored code with no enum entry (one retired since the row was
    // written) falls back to the generic rejection message rather than surfacing a raw key.
    private @Nullable String failureMessage(@Nullable String failureCode) {
        if (failureCode == null) {
            return null;
        }
        MediaFailureCode code = MediaFailureCode.fromCode(failureCode).orElse(MediaFailureCode.REJECTED);
        return messageSource.getMessage(code.messageKey(), null, LocaleContextHolder.getLocale());
    }

    private static <T> Map<UUID, T> byId(List<T> rows, Function<T, UUID> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}

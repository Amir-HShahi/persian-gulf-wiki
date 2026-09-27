package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaResponse;
import com.persiangulfwiki.core.dev.exception.InvalidDevTestMediaRequestException;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaFailureCode;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.PanoramaLink;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.exception.MediaNotFoundException;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;
import com.persiangulfwiki.core.media.service.MediaProcessingService;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

// Mints a throwaway gallery item at any point in its life -- UPLOADING, PROCESSING, READY
// awaiting review, PUBLISHED, REJECTED, FAILED, HIDDEN -- plus, optionally, later metadata
// versions in any status and panorama hotspots on any version, for E2E suites that need one without driving a real upload, a
// pipeline run and a moderator decision.
//
// Two independent gates keep this out of production, exactly as for the other fixture
// controllers: @Profile("dev") here, so the bean does not exist, and DevSecurityConfig's
// profiled chain, which already matches the whole /api/dev/** prefix.
//
// Deliberately bypasses MediaUploadService and MediaModerationService: READY is only reachable
// through the pipeline and PUBLISHED/HIDDEN only through a moderator, so writing rows directly
// is what makes those states available in one call. Nothing is put in storage -- variant keys
// point at objects that do not exist, so the URLs the read path builds for them (public or
// signed) are well-formed but 404; fine for every test that reads rows rather than bytes. The
// schema's own CHECKs still hold (ck_article_media_published_has_metadata in particular),
// because a fixture the read path would have to special-case is no fixture.
//
// Items created through the *real* reserve route during an E2E run carry no marker and so can
// never be deleted here; they go away with their article, since article_media.article_id is ON
// DELETE CASCADE -- upload them to an article minted by /api/dev/test-articles.
@RestController
@RequestMapping("/api/dev/test-media")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Media", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestMediaController {

    // DevUserSeeder's contributor, never swept -- see DevTestArticleController.resolveAuthorUserId
    // for the sweep-ordering race a freshly minted uploader would reintroduce (uploaded_by and
    // submitted_by are both RESTRICT, V18).
    private static final String SEEDED_UPLOADER_EMAIL = "contributor@dev.local";
    private static final int SLUG_LENGTH = 12;
    private static final long FIXTURE_BYTES = 1024;
    private static final String FIXTURE_PLACEHOLDER = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final PanoramaLinkRepository panoramaLinkRepository;
    private final ArticleRepository articleRepository;
    private final DevTestArticleFixtures articleFixtures;
    private final UserRepository userRepository;

    @Operation(summary = "Mint a disposable test gallery item", description = "Creates a gallery item marked as machine-minted on an existing "
            + "article (or, when no article id is sent, on a throwaway approved article minted with it), at any processing/publication status pair, with its first metadata version in "
            + "the status that pair implies (approved for published or hidden, rejected for rejected, "
            + "awaiting review otherwise). Optionally adds later metadata versions, one per status given "
            + "(the newest approved one becomes current), and panorama hotspots from any version (by "
            + "default the newest) to the given items. No file is stored.")
    @ApiResponse(responseCode = "201", description = "The created item and the ids of everything minted with it.")
    @ApiResponse(responseCode = "400", description = "An approved later version was asked for on an item whose first version is not "
            + "approved (it is not published or hidden), `panoramaLinkVersionNumber` names no version "
            + "being minted, or a hotspot target id is blank.")
    @ApiResponse(responseCode = "404", description = "The article id names no article, or a hotspot target id names no "
            + "gallery item.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Bare DTO rather than the usual envelope, matching the other fixture controllers.
    public DevTestMediaResponse mint(@RequestBody(required = false) DevTestMediaRequest request) {
        DevTestMediaRequest safeRequest = request != null
                ? request
                : new DevTestMediaRequest(null, null, null, null, null, null, null, null);

        UUID articleId = safeRequest.articleId();
        if (articleId == null) {
            // Nothing to attach to: mint a throwaway APPROVED article (so the gallery is publicly
            // readable, like any live article) instead of making every suite set one up first.
            // It carries the dev marker, so DevTestArticleSweeper reclaims it; this route's own
            // DELETE leaves it alone because it cannot know a caller-supplied article from this
            // one, and only ever removes the media it minted.
            articleId = articleFixtures.mint(new DevTestArticleRequest(
                    null, null, null, null, RevisionStatus.APPROVED, null, null, null, null)).articleId();
        } else if (!articleRepository.existsById(articleId)) {
            throw new ArticleNotFoundException();
        }
        List<UUID> linkTargetIds = safeRequest.panoramaLinkTargetIds() != null
                ? safeRequest.panoramaLinkTargetIds()
                : List.of();
        for (UUID targetId : linkTargetIds) {
            // A blank string in the JSON list deserializes to null, and existsById(null) throws.
            if (targetId == null) {
                throw new InvalidDevTestMediaRequestException();
            }
            if (!articleMediaRepository.existsById(targetId)) {
                throw new MediaNotFoundException();
            }
        }

        List<MetadataVersionStatus> laterStatuses = safeRequest.laterVersionStatuses() != null
                ? safeRequest.laterVersionStatuses()
                : List.of();
        int linkVersionNumber = safeRequest.panoramaLinkVersionNumber() != null
                ? safeRequest.panoramaLinkVersionNumber()
                : laterStatuses.size() + 1;
        if (linkVersionNumber < 1 || linkVersionNumber > laterStatuses.size() + 1) {
            throw new InvalidDevTestMediaRequestException();
        }

        MediaKind type = safeRequest.type() != null ? safeRequest.type() : MediaKind.IMAGE;
        ProcessingStatus processingStatus = safeRequest.processingStatus() != null
                ? safeRequest.processingStatus()
                : ProcessingStatus.UPLOADING;
        PublicationStatus publicationStatus = safeRequest.publicationStatus() != null
                ? safeRequest.publicationStatus()
                : PublicationStatus.PENDING;
        UUID uploaderId = resolveUploaderUserId(safeRequest.uploadedByUserId());
        String sha256 = randomSha256();
        boolean isProcessed = processingStatus == ProcessingStatus.READY;

        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(type)
                .uploadedBy(uploaderId)
                .declaredContentType(type == MediaKind.VIDEO ? "video/mp4" : "image/jpeg")
                .declaredBytes(FIXTURE_BYTES)
                .declaredSha256(sha256)
                .sha256(isProcessed ? sha256 : null)
                .processingStatus(processingStatus)
                .failureCode(processingStatus == ProcessingStatus.FAILED ? MediaFailureCode.UPLOAD_MISMATCH.code() : null)
                // Inserted PENDING and moved to its final status below, once the first metadata
                // version exists: ck_article_media_published_has_metadata refuses a PUBLISHED
                // row with no current version, and the version needs this row's id first.
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .placeholder(isProcessed ? FIXTURE_PLACEHOLDER : null)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        MetadataVersionStatus firstStatus = firstVersionStatus(publicationStatus);
        if (firstStatus != MetadataVersionStatus.APPROVED && laterStatuses.contains(MetadataVersionStatus.APPROVED)) {
            throw new InvalidDevTestMediaRequestException();
        }
        MediaMetadataVersion first = saveVersion(media.getId(), 1, uploaderId, firstStatus);
        List<MediaMetadataVersion> versions = new ArrayList<>(List.of(first));
        for (MetadataVersionStatus status : laterStatuses) {
            versions.add(saveVersion(media.getId(), versions.size() + 1, uploaderId, status));
        }
        MediaMetadataVersion current = versions.reversed().stream()
                .filter(version -> version.getStatus() == MetadataVersionStatus.APPROVED)
                .findFirst()
                .orElse(null);
        if (current != null) {
            media.setCurrentMetadataVersionId(current.getId());
            media.setPublishedAt(Instant.now());
        }
        media.setPublicationStatus(publicationStatus);
        if (isProcessed) {
            media.setVariants(fixtureVariants(media.getId(), firstStatus == MetadataVersionStatus.APPROVED));
        }
        articleMediaRepository.save(media);

        UUID linkVersionId = versions.get(linkVersionNumber - 1).getId();

        List<UUID> linkIds = new ArrayList<>();
        for (int i = 0; i < linkTargetIds.size(); i++) {
            linkIds.add(panoramaLinkRepository.save(PanoramaLink.builder()
                    .metadataVersionId(linkVersionId)
                    .toMediaId(linkTargetIds.get(i))
                    // Spread around the horizon so a viewer test sees distinct hotspots.
                    .yawDeg(BigDecimal.valueOf((i * 30L) % 360))
                    .pitchDeg(BigDecimal.ZERO)
                    .label("fixture link " + (i + 1))
                    .build()).getId());
        }

        return new DevTestMediaResponse(media.getId(), articleId, uploaderId, processingStatus, publicationStatus,
                first.getId(), versions.stream().skip(1).map(MediaMetadataVersion::getId).toList(),
                current != null ? current.getId() : null, List.copyOf(linkIds));
    }

    @Operation(summary = "Delete a minted test gallery item", description = "Deletes an item previously created by this endpoint, together "
            + "with its metadata versions, hotspots in either direction, and any moderation tasks on "
            + "them. Refuses anything else -- an item uploaded through the real flow is reported as "
            + "404 rather than deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The item was deleted.")
    @ApiResponse(responseCode = "404", description = "No such item, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID mediaId) {
        // The marker check is what makes accepting an arbitrary id safe -- see
        // DevTestSubjectController.delete. Nothing in storage to clean up: this endpoint never
        // wrote any objects.
        ArticleMedia media = articleMediaRepository.findById(mediaId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(MediaNotFoundException::new);
        articleMediaRepository.delete(media);
    }

    // What the real flow would have left the first version at, given where the item ended up:
    // an item only becomes PUBLISHED (and later, possibly, HIDDEN) by its first version being
    // approved, and only becomes REJECTED by it being rejected.
    private static MetadataVersionStatus firstVersionStatus(PublicationStatus publicationStatus) {
        return switch (publicationStatus) {
            case PUBLISHED, HIDDEN -> MetadataVersionStatus.APPROVED;
            case REJECTED -> MetadataVersionStatus.REJECTED;
            case PENDING -> MetadataVersionStatus.PENDING_REVIEW;
        };
    }

    private MediaMetadataVersion saveVersion(UUID mediaId, int number, UUID submittedBy, MetadataVersionStatus status) {
        return mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                .mediaId(mediaId)
                .versionNumber(number)
                .submittedBy(submittedBy)
                .status(status)
                .build());
    }

    // Keyed where the real flow would have left the file: under the item's pending prefix until
    // it is approved, under its public prefix after (MediaVariantService.publish moves it). The
    // read path builds URLs from that layout and leaves out a variant that does not follow it.
    private static List<MediaVariant> fixtureVariants(UUID mediaId, boolean isApproved) {
        String prefix = isApproved
                ? MediaVariantService.publicVariantPrefix(mediaId)
                : MediaProcessingService.pendingVariantPrefix(mediaId);
        return new ArrayList<>(List.of(new MediaVariant("640", "webp", prefix + "640.webp", 512, 640, 320)));
    }

    // Random rather than fixed so two fixtures on one article never trip the duplicate check.
    private static String randomSha256() {
        byte[] digest = new byte[32];
        RANDOM.nextBytes(digest);
        return Base64.getEncoder().encodeToString(digest);
    }

    private UUID resolveUploaderUserId(UUID requestedUploaderUserId) {
        if (requestedUploaderUserId != null) {
            return requestedUploaderUserId;
        }
        return userRepository.findByEmail(SEEDED_UPLOADER_EMAIL)
                .map(User::getId)
                .orElseGet(this::mintDisposableUploader);
    }

    private UUID mintDisposableUploader() {
        String slug = UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        User uploader = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .enabled(true)
                .emailVerified(true)
                .build());
        return uploader.getId();
    }
}

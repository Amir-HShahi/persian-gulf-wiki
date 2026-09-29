package com.persiangulfwiki.core.media.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.media.dto.MediaMetadataRequest;
import com.persiangulfwiki.core.media.dto.MediaMetadataVersionResponse;
import com.persiangulfwiki.core.media.dto.MediaResponse;
import com.persiangulfwiki.core.media.dto.ReserveMediaRequest;
import com.persiangulfwiki.core.media.dto.ReserveMediaResponse;
import com.persiangulfwiki.core.media.service.MediaMetadataVersionService;
import com.persiangulfwiki.core.media.service.MediaReadService;
import com.persiangulfwiki.core.media.service.MediaUploadService;
import com.persiangulfwiki.core.web.SignedUrlResponses;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Deliberately NOT annotated @Validated -- same reasoning as ArticleController: it would turn
// constraint failures on path/query parameters into a 500 instead of the 400 Spring's own
// method validation already produces.
//
// Nested under /api/articles because a gallery item cannot exist without its article. The GET
// routes therefore fall under PublicRoutes.CONTENT_READS (anonymous GET on /api/articles/**),
// and MediaReadService/MediaMetadataVersionService decide what an anonymous or non-owning caller
// may see. The POSTs are covered by the main chain's anyRequest().authenticated() plus CSRF, like
// every other write.
//
// "complete" is an action sub-resource, matching the existing .../submit, .../claim and
// .../decide routes: it records that the client's direct-to-storage PUT has finished, which no
// plain CRUD verb on the media resource expresses. metadata-versions, by contrast, is a real
// child collection: each POST creates a version row, and the GET lists them.
@RestController
@RequestMapping("/api/articles/{articleId}/media")
@RequiredArgsConstructor
@Tag(name = "Article Media", description = "An article's gallery: images, videos and 360° panoramas contributed by any "
        + "signed-in, verified account. A file is never sent to this API. The client reserves an upload, "
        + "PUTs the file straight to storage with the returned presigned URL, then confirms it. The "
        + "server checks the stored file against the declaration, processes it in the background, and "
        + "queues it for moderation. Nothing is public until a moderator approves it. Until then an "
        + "item is visible only to its uploader and to moderators; to anyone else it looks exactly "
        + "like one that does not exist (404).")
public class MediaController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String MODERATOR_AUTHORITY = "ROLE_MODERATOR";

    private final MediaUploadService mediaUploadService;
    private final MediaReadService mediaReadService;
    private final MediaMetadataVersionService mediaMetadataVersionService;
    private final MessageSource messageSource;
    private final RoleHierarchy roleHierarchy;

    @Operation(summary = "Reserve a gallery upload", description = "Declares the file about to be uploaded and returns a presigned URL "
            + "to PUT it to, valid for about 10 minutes. The PUT must go straight to `uploadUrl` (not to "
            + "this API), carry every header in `requiredHeaders` verbatim, and have a body of exactly "
            + "`bytes` bytes whose SHA-256 is `sha256`. Storage refuses anything else. Then call "
            + "\"Confirm a gallery upload\". The item starts as UPLOADING/PENDING. An item never "
            + "confirmed is deleted after 24 hours. Each account is limited in how many uploads it may "
            + "reserve per hour, how many items it may have awaiting processing or review at once, "
            + "and how many bytes it may store in total. An article with nothing approved yet can "
            + "only receive uploads from the people who can see it: its authors and moderators. A 360° "
            + "panorama may be uploaded with hotspots (`metadata.links`) to other panoramas, under the same "
            + "rules as in \"Propose a metadata edit\".")
    @ApiResponse(responseCode = "201", description = "The reservation: the new item's id and where and how to upload the file.")
    @ApiResponse(responseCode = "400", description = "One of: field validation failed (see `errors`); the article id in the path is not a "
            + "valid UUID; `contentType` is not accepted for this `type`; `bytes` exceeds the limit for "
            + "this `type`; `metadata.shotAtOffset` was sent without `metadata.shotAt`, or "
            + "`metadata.headingRef` without `metadata.headingDeg`; or a hotspot in `metadata.links` breaks a "
            + "rule: hotspots sent for a `type` other than PANORAMA_360, more than 20 of them, or a target "
            + "that is not a 360° panorama. Read `code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie. Read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "One of: no article with that id, or the article has nothing approved yet and the "
            + "caller is not one of its authors or a moderator (the two look the same on purpose); or a "
            + "hotspot target in `metadata.links` does not exist or is not visible to the caller. Read "
            + "`code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "One of: this file (same SHA-256) is already in the article's gallery or on its way "
            + "there; the account already has the maximum number of items awaiting processing or "
            + "review; or this upload would take the account over its storage quota. Read `code` "
            + "for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "The account has reserved too many uploads in the current window. The `Retry-After` "
            + "header gives the number of seconds until it resets.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ReserveMediaResponse> reserve(@PathVariable UUID articleId,
            @Valid @RequestBody ReserveMediaRequest request) {
        ReserveMediaResponse data =
                mediaUploadService.reserve(currentUserId(), isCurrentUserModerator(), articleId, request);
        String message = messageSource.getMessage("success.mediaReserved", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Confirm a gallery upload", description = "Call once the PUT to `uploadUrl` has succeeded. The server compares "
            + "the stored file's size and SHA-256 with what was declared at reservation. On a match the "
            + "item moves to PROCESSING. Poll \"Get a gallery item\" until it is READY or FAILED. On a "
            + "mismatch, or if nothing was uploaded, the item moves to FAILED with a `failureCode`, and "
            + "the call still succeeds: the failure is reported in the returned item, not as an error "
            + "status. Only the account that reserved the upload may confirm it, and only once.")
    @ApiResponse(responseCode = "200", description = "The item after verification: PROCESSING, or FAILED with `failureCode` "
            + "upload_missing (nothing was uploaded) or upload_mismatch (size or checksum differs; the "
            + "stored file has been deleted).")
    @ApiResponse(responseCode = "400", description = "An id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "One of: the caller did not reserve this upload; the account's email address is not "
            + "yet verified; or the CSRF header is missing or does not match the cookie. Read "
            + "`detail`/`code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no gallery item with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The item is no longer waiting for its upload: it was already confirmed, or it "
            + "already failed.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/{mediaId}/complete")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<MediaResponse> complete(@PathVariable UUID articleId, @PathVariable UUID mediaId) {
        MediaResponse data = mediaUploadService.complete(currentUserId(), articleId, mediaId);
        String message = messageSource.getMessage("success.mediaUploadCompleted", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List an article's gallery", description = "Open to everyone; no account is required, and the result is the "
            + "same whoever asks: only items that are processed and approved are listed, so an uploader's "
            + "own pending items are not (read those one by one with \"Get a gallery item\"). Newest "
            + "approval first, paged. Every rendition URL here is a permanent public URL. `description` is "
            + "the caption in `language`, if any. For 360° panoramas, `links` holds the hotspots to other "
            + "panoramas whose target is itself public; each carries enough of the target (its placeholder "
            + "and smallest rendition) to draw a preview without another request. Sending the session cookie "
            + "is optional; it only matters for an article that has nothing approved yet, which is listed "
            + "only to its authors and moderators.")
    @ApiResponse(responseCode = "200", description = "One page of gallery items. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "The article id in the path is not a valid UUID, `language` is longer than 20 "
            + "characters, or `page`/`size` is outside its allowed range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No article with that id, or the article has nothing approved yet and the caller is "
            + "not one of its authors or a moderator. The two cases look the same on purpose, and neither is "
            + "ever a 401 or 403.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "language", description = "Optional BCP-47 language tag, e.g. \"fa\", \"en\", \"ar\", selecting which caption "
            + "`description` carries. Matched exactly; omit it to get no captions.")
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<MediaResponse>> list(@PathVariable UUID articleId,
            @RequestParam(required = false) @Size(max = 20, message = "{validation.language.tooLong}") String language,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        List<MediaResponse> data = mediaReadService.listPublic(currentUserIdOrNull(), isCurrentUserModerator(), articleId,
                language, PageRequest.of(page, size));
        String message = messageSource.getMessage("success.mediaListed", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a gallery item", description = "The item with its renditions, metadata, caption and hotspots, plus its "
            + "processing and publication status, which is what an uploader polls after confirming an upload. "
            + "Its uploader and moderators can always read it. Anyone else, signed in or not, can read it only "
            + "once it is processed and approved, and only on an article they can see. For an item that is not "
            + "public yet, rendition URLs are signed and expire within minutes: fetch the item again for fresh "
            + "ones rather than storing them. The response is never cacheable and asks browsers not to send "
            + "it as a referrer. Sending the session cookie is optional. Without it, or with one whose email is "
            + "not yet verified or whose password setup is unfinished, the caller is treated as anonymous.")
    @ApiResponse(responseCode = "200", description = "The item. Once processing finishes it is READY (and then waits for a "
            + "moderator), or FAILED with a `failureCode` and a `failureMessage` to show the uploader. "
            + "Processing normally takes seconds to minutes; an item that stays PROCESSING past the "
            + "server's timeout (an hour or two) is failed with `processing_timeout`.")
    @ApiResponse(responseCode = "400", description = "An id in the path is not a valid UUID, or `language` is longer than 20 characters.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no gallery item with that id, or the caller may not see it yet. "
            + "The two look the same on purpose, and neither is ever a 401 or 403.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "language", description = "Optional BCP-47 language tag selecting which caption `description` carries. "
            + "Matched exactly; omit it to get no caption.")
    @GetMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<MediaResponse> get(@PathVariable UUID articleId, @PathVariable UUID mediaId,
            @RequestParam(required = false) @Size(max = 20, message = "{validation.language.tooLong}") String language,
            HttpServletResponse response) {
        MediaResponse data = mediaReadService.get(currentUserIdOrNull(), isCurrentUserModerator(), articleId, mediaId,
                language);
        SignedUrlResponses.markPrivate(response);
        String message = messageSource.getMessage("success.mediaFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Propose a metadata edit", description = "Proposes a new version of what is said about a gallery item: when "
            + "and where it was shot, which way it faces, its captions and, for a 360° panorama, its hotspots. "
            + "The version is a complete replacement: send every field, caption and hotspot it should have, "
            + "because anything omitted is absent from it (nothing is copied from the current version). "
            + "What the public sees does not change until a moderator approves the proposal; if several "
            + "are pending, approving one closes every older pending one, and a version older than the "
            + "current one can no longer be approved. Only the item's uploader and moderators may propose "
            + "an edit, and only while the item is processing, awaiting review or published. A hotspot's "
            + "target must be another 360° panorama the caller can see: approved on an article they can "
            + "read, or one they uploaded themselves. It may belong to another article, and hotspots are "
            + "one-way. A hotspot whose target is later hidden or rejected is kept, and simply not shown "
            + "while the target is not public. Accounts other than moderators are limited in how many "
            + "edits they may propose per hour and how many may await review at once.")
    @ApiResponse(responseCode = "201", description = "The proposed version, awaiting review. If the item is already processed it is "
            + "queued for moderation now; otherwise it is queued once processing finishes.")
    @ApiResponse(responseCode = "400", description = "One of: field validation failed (see `errors`); an id in the path is not a valid "
            + "UUID; the body is missing or not valid JSON; `shotAtOffset` was sent without `shotAt`, or "
            + "`headingRef` without `headingDeg`; or a hotspot breaks a rule: hotspots on an item that is "
            + "not a 360° panorama, more than 20 of them, a hotspot leading to the item itself, or a target "
            + "that is not a 360° panorama. Read `code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "One of: the item is public but the caller is neither its uploader nor a "
            + "moderator; the account's email address is not yet verified; or the CSRF header is missing or "
            + "does not match the cookie. Read `detail`/`code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "One of: the article has no gallery item with that id, or the item is not public "
            + "and the caller is neither its uploader nor a moderator (the two look the same on purpose); or "
            + "a hotspot target does not exist or is not visible to the caller. Read `code` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "One of: the item does not accept edits in its current state (its upload was "
            + "never confirmed, or it failed, was rejected or is hidden); or the account already has the "
            + "maximum number of edits awaiting review. Read `code` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "The account has proposed too many edits in the current window. The `Retry-After` "
            + "header gives the number of seconds until it resets.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/{mediaId}/metadata-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<MediaMetadataVersionResponse> proposeMetadataVersion(@PathVariable UUID articleId,
            @PathVariable UUID mediaId, @Valid @RequestBody MediaMetadataRequest request) {
        MediaMetadataVersionResponse data = mediaMetadataVersionService.propose(currentUserId(), isCurrentUserModerator(),
                articleId, mediaId, request);
        String message = messageSource.getMessage("success.metadataVersionProposed", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List a gallery item's metadata versions", description = "The item's metadata history, oldest first, each "
            + "version in full: its fields, its caption in every language, and its hotspots. The item's "
            + "uploader and moderators see every version, including those awaiting review and those "
            + "rejected. Anyone else sees only approved versions, and only of an item they could read on "
            + "its own. Hotspots are limited to targets the caller may see: for a moderator every target, "
            + "for anyone else public ones plus their own uploads; a target that is not public has no "
            + "preview. Sending the session cookie is optional.")
    @ApiResponse(responseCode = "200", description = "The versions, oldest first.")
    @ApiResponse(responseCode = "400", description = "An id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The article has no gallery item with that id, or the caller may not see it. The two "
            + "look the same on purpose, and neither is ever a 401 or 403.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{mediaId}/metadata-versions")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<MediaMetadataVersionResponse>> listMetadataVersions(@PathVariable UUID articleId,
            @PathVariable UUID mediaId) {
        List<MediaMetadataVersionResponse> data = mediaMetadataVersionService.list(currentUserIdOrNull(),
                isCurrentUserModerator(), articleId, mediaId);
        String message = messageSource.getMessage("success.metadataVersionsListed", null,
                LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }

    // For the public GET, where the caller may be anonymous -- see ArticleController.
    private @Nullable UUID currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return UUID.fromString(authentication.getName());
    }

    // Through the RoleHierarchy so ADMIN counts as a moderator, same as ArticleController.
    private boolean isCurrentUserModerator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .anyMatch(authority -> MODERATOR_AUTHORITY.equals(authority.getAuthority()));
    }
}

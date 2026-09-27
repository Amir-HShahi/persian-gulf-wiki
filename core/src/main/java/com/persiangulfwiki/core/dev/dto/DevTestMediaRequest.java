package com.persiangulfwiki.core.dev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;

import java.util.List;
import java.util.UUID;

// Every field optional. Omitting articleId mints a throwaway APPROVED article to attach the item
// to (its id comes back in the response); pass one only to target a specific article, e.g. one
// from POST /api/dev/test-articles.
//
// processingStatus x publicationStatus is the point of this endpoint: any pair is accepted,
// including ones the real flow can only reach through the pipeline and a moderator
// (READY+PENDING, PUBLISHED, HIDDEN). The first metadata version's status follows the
// publication status the way the real flow would have left it -- see
// DevTestMediaController.firstVersionStatus.
//
// laterVersionStatuses adds versions 2, 3, ... on top, one per entry, in any mix: pending edits,
// approved ones (the newest approved version becomes current) and rejected ones. The one refused
// combination is an approved later version on an item whose first version was never approved --
// that would give an unpublished item a current version, which nothing in the real flow does.
// Orderings the real flow now prevents on its own (a pending version older than the current one,
// a pending version on a rejected item) are allowed on purpose: they are how a suite reaches the
// decide route's METADATA_VERSION_SUPERSEDED and MEDIA_ALREADY_REJECTED refusals.
//
// panoramaLinkTargetIds attach one hotspot per id to version panoramaLinkVersionNumber (default:
// the newest), and deliberately skip the service's rules (both ends PANORAMA_360, no self-link,
// at most 20, target visibility) so a suite can assert how a renderer copes with a link the real
// flow would have refused.
public record DevTestMediaRequest(
        UUID articleId,
        MediaKind type,
        ProcessingStatus processingStatus,
        PublicationStatus publicationStatus,
        UUID uploadedByUserId,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<MetadataVersionStatus> laterVersionStatuses,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<UUID> panoramaLinkTargetIds,
        Integer panoramaLinkVersionNumber) {
}

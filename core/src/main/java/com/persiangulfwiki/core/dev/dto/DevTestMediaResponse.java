package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;

import java.util.List;
import java.util.UUID;

// Enough ids to drive the real routes afterwards without a second lookup: the media id for
// GET/complete, the metadata version ids for POST /api/dev/test-moderation-tasks, and the link
// ids for asserting what the read path filters. laterMetadataVersionIds holds versions 2, 3, ...
// in request order (empty unless requested); currentMetadataVersionId is the newest approved
// version, or null; panoramaLinkIds is empty unless links were requested.
public record DevTestMediaResponse(
        UUID mediaId,
        UUID articleId,
        UUID uploadedByUserId,
        ProcessingStatus processingStatus,
        PublicationStatus publicationStatus,
        UUID firstMetadataVersionId,
        List<UUID> laterMetadataVersionIds,
        UUID currentMetadataVersionId,
        List<UUID> panoramaLinkIds) {
}

package com.persiangulfwiki.core.media.entity;

import io.swagger.v3.oas.annotations.media.Schema;

//   PENDING   -- never approved. Visible only to its uploader and to moderators.
//   PUBLISHED -- its first metadata version was approved; public once also READY.
//   REJECTED  -- its first metadata version was rejected; swept after a grace period.
//   HIDDEN    -- was published, now withdrawn from the public (reports, later phase) until a
//                moderator reviews it. Never deleted by that transition.
@Schema(description = PublicationStatus.VALUES_DOC)
public enum PublicationStatus {
    PENDING,
    PUBLISHED,
    REJECTED,
    HIDDEN;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `PENDING`: never approved; visible only to its uploader and to moderators.
            - `PUBLISHED`: approved; public once its processing status is also READY.
            - `REJECTED`: refused; removed after a grace period.
            - `HIDDEN`: was published, now withdrawn from the public until a moderator reviews it.""";
}

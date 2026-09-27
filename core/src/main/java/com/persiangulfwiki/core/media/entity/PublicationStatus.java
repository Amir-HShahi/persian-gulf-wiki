package com.persiangulfwiki.core.media.entity;

//   PENDING   -- never approved. Visible only to its uploader and to moderators.
//   PUBLISHED -- its first metadata version was approved; public once also READY.
//   REJECTED  -- its first metadata version was rejected; swept after a grace period.
//   HIDDEN    -- was published, now withdrawn from the public (reports, later phase) until a
//                moderator reviews it. Never deleted by that transition.
public enum PublicationStatus {
    PENDING,
    PUBLISHED,
    REJECTED,
    HIDDEN
}

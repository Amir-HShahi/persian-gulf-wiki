package com.persiangulfwiki.core.media.event;

import java.util.UUID;

// Published by MediaProcessingService when a gallery item reaches READY, once per metadata
// version of it that is awaiting review: a moderation task should now exist for that version.
//
// Same reason for being an event as RevisionSubmittedEvent, and the same rule for the
// listener: moderation already depends on media (MediaModerationService), so media cannot call
// ModerationService back, and the listener must stay a plain synchronous @EventListener so the
// item turning READY and its task being opened commit together -- an AFTER_COMMIT listener that
// then failed would leave a READY item no moderator can ever find.
public record MediaVersionAwaitingReviewEvent(UUID metadataVersionId) {
}

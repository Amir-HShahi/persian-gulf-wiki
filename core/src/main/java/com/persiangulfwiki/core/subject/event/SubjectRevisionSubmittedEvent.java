package com.persiangulfwiki.core.subject.event;

import java.util.UUID;

// Published by SubjectRevisionService.submit once a subject revision is PENDING. The subject
// package's counterpart of the article package's RevisionSubmittedEvent, for the same reason and
// under the same rule: moderation depends on this package, so this package announces rather than
// calls back, and the listener must stay a plain synchronous @EventListener so the revision and
// its moderation task commit together. Read RevisionSubmittedEvent before changing that.
public record SubjectRevisionSubmittedEvent(UUID revisionId, boolean firstSubmission) {
}

package com.persiangulfwiki.core.article.event;

import java.util.UUID;

// Published by ArticleRevisionService.submit once a revision has actually moved to PENDING.
//
// This exists to solve a dependency-direction problem, and the reasoning is worth stating
// because the alternative looks simpler and is wrong. Phase 2 deliberately left submit as a
// bare status move, with a TODO saying Phase 3 must extend that same endpoint rather than
// add a second submit route. But moderation already depends on article -- it flips a
// revision's status and a translation's currentRevisionId when a decision is made -- so
// having ArticleRevisionService call ModerationService back would make the two feature
// packages mutually dependent, which is exactly what that TODO warned against.
//
// An event inverts the direction: article announces that something happened and knows
// nothing about who cares. The listener lives in moderation (see ModerationService), is
// plain @EventListener rather than @TransactionalEventListener, and therefore runs
// synchronously inside submit's own transaction -- so the revision reaching PENDING and its
// task being opened either both commit or neither does. Do not "improve" this to
// @TransactionalEventListener(AFTER_COMMIT): that would let a revision go PENDING with no
// task, permanently invisible to the moderator queue, if the listener then failed.
//
// This is the first and so far only use of Spring's event publishing in this codebase. It is
// a deliberate exception for a cross-feature notification, not a pattern to reach for inside
// a single feature -- a direct method call is clearer everywhere the direction already
// allows one.
//
// firstSubmission distinguishes a revision reaching PENDING for the very first time (from
// DRAFT) from an author resubmitting it after a REQUEST_CHANGES round. The listener can
// re-derive this by looking for an existing task and therefore does not depend on it -- it is
// carried so the listener can check the two accounts agree and say so when they don't, rather
// than silently resolving a drift between the revision's status and the task table.
public record RevisionSubmittedEvent(UUID revisionId, boolean firstSubmission) {
}

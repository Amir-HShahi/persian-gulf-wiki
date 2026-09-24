package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

// 403 rather than 409: the caller holds the MODERATOR role, so the request is well-formed and
// the task is in a decidable state -- it is this particular moderator who may not act on it,
// which is an authorization outcome. Reuses ForbiddenException, the base Phase 2 introduced
// for exactly this shape of domain-level ownership check (see NotRevisionAuthorException).
public class NotTaskClaimantException extends ForbiddenException {

    private static final String DEFAULT_MESSAGE = "caller is not the moderator who claimed this task";

    public NotTaskClaimantException() {
        super(DEFAULT_MESSAGE);
    }

    public NotTaskClaimantException(String message) {
        super(message);
    }
}

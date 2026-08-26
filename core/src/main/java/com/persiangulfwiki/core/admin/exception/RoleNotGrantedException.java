package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

// Thrown when revoking a (role, entityType) pair the target user doesn't actually hold —
// mirrors SessionNotFoundException's NOT_FOUND shape rather than a plain 400, since this is
// "that grant doesn't exist" in the same sense a missing session id doesn't exist.
public class RoleNotGrantedException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "role not granted";

    public RoleNotGrantedException() {
        super(DEFAULT_MESSAGE);
    }

    public RoleNotGrantedException(String message) {
        super(message);
    }
}

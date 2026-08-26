package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class RoleAlreadyGrantedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "role already granted";

    public RoleAlreadyGrantedException() {
        super(DEFAULT_MESSAGE);
    }

    public RoleAlreadyGrantedException(String message) {
        super(message);
    }
}

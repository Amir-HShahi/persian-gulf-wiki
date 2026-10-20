package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class RoleAlreadyGrantedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "role already granted";

    public RoleAlreadyGrantedException() {
        super(DEFAULT_MESSAGE);
    }

    public RoleAlreadyGrantedException(String message) {
        super(message);
    }
}

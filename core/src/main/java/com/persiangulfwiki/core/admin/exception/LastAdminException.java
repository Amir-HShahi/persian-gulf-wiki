package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class LastAdminException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "cannot revoke the last remaining admin";

    public LastAdminException() {
        super(DEFAULT_MESSAGE);
    }

    public LastAdminException(String message) {
        super(message);
    }
}

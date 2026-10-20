package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class LastAdminException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "cannot revoke the last remaining admin";

    public LastAdminException() {
        super(DEFAULT_MESSAGE);
    }

    public LastAdminException(String message) {
        super(message);
    }
}

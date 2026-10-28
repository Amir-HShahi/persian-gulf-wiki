package com.persiangulfwiki.core.revisioncheck.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class RevisionCheckNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "revision check not found";

    public RevisionCheckNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionCheckNotFoundException(String message) {
        super(message);
    }
}

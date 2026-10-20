package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicatePendingApplicationException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "a pending application for this expertise already exists";

    public DuplicatePendingApplicationException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicatePendingApplicationException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicatePendingApplicationException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "a pending application for this entity type already exists";

    public DuplicatePendingApplicationException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicatePendingApplicationException(String message) {
        super(message);
    }
}

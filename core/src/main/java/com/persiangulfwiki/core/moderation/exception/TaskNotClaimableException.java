package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class TaskNotClaimableException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "moderation task is not open for claiming";

    public TaskNotClaimableException() {
        super(DEFAULT_MESSAGE);
    }

    public TaskNotClaimableException(String message) {
        super(message);
    }
}

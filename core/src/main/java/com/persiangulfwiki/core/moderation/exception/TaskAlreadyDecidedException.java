package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class TaskAlreadyDecidedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "moderation task has already been decided";

    public TaskAlreadyDecidedException() {
        super(DEFAULT_MESSAGE);
    }

    public TaskAlreadyDecidedException(String message) {
        super(message);
    }
}

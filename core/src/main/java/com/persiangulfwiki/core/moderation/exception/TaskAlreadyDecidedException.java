package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class TaskAlreadyDecidedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "moderation task has already been decided";

    public TaskAlreadyDecidedException() {
        super(DEFAULT_MESSAGE);
    }

    public TaskAlreadyDecidedException(String message) {
        super(message);
    }
}

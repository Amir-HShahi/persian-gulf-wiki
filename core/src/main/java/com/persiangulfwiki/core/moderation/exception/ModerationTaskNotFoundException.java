package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class ModerationTaskNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "no moderation task with that id";

    public ModerationTaskNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ModerationTaskNotFoundException(String message) {
        super(message);
    }
}

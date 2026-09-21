package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class ModerationTaskNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "no moderation task with that id";

    public ModerationTaskNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ModerationTaskNotFoundException(String message) {
        super(message);
    }
}

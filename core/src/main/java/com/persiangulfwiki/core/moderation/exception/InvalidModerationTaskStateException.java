package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class InvalidModerationTaskStateException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "unknown moderation task state filter";

    public InvalidModerationTaskStateException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidModerationTaskStateException(String message) {
        super(message);
    }
}

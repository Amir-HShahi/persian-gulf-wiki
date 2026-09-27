package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class PendingMediaLimitExceededException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "too many media items awaiting processing or review";

    public PendingMediaLimitExceededException() {
        super(DEFAULT_MESSAGE);
    }

    public PendingMediaLimitExceededException(String message) {
        super(message);
    }
}

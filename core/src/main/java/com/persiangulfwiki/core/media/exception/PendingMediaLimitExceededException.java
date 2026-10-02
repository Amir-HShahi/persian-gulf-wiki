package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class PendingMediaLimitExceededException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "too many media items awaiting processing or review";

    public PendingMediaLimitExceededException() {
        super(DEFAULT_MESSAGE);
    }

    public PendingMediaLimitExceededException(String message) {
        super(message);
    }
}

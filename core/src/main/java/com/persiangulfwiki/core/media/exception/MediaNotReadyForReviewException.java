package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// Approving an item's first metadata version publishes the file along with it, so it is
// refused until the file is READY -- a moderator must never publish something unprocessed.
public class MediaNotReadyForReviewException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "media item has not finished processing";

    public MediaNotReadyForReviewException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaNotReadyForReviewException(String message) {
        super(message);
    }
}

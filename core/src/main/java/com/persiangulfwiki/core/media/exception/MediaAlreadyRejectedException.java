package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// Approving a metadata version of an item that was rejected as a whole. Nothing may publish it
// again; a new upload is the way back.
public class MediaAlreadyRejectedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "media item was rejected";

    public MediaAlreadyRejectedException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaAlreadyRejectedException(String message) {
        super(message);
    }
}

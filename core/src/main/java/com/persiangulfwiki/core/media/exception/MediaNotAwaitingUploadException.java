package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// /complete on an item that has already left UPLOADING -- a repeated call, or one after the
// abandoned-upload sweep or a failed verification already moved it on.
public class MediaNotAwaitingUploadException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "media item is not awaiting an upload";

    public MediaNotAwaitingUploadException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaNotAwaitingUploadException(String message) {
        super(message);
    }
}

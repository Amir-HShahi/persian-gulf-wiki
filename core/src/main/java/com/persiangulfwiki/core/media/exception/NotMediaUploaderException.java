package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

public class NotMediaUploaderException extends ForbiddenException {

    private static final String DEFAULT_MESSAGE = "caller is not the media item's uploader";

    public NotMediaUploaderException() {
        super(DEFAULT_MESSAGE);
    }

    public NotMediaUploaderException(String message) {
        super(message);
    }
}

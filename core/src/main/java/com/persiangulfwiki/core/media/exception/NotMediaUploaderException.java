package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

import java.io.Serial;

public class NotMediaUploaderException extends ForbiddenException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "caller is not the media item's uploader";

    public NotMediaUploaderException() {
        super(DEFAULT_MESSAGE);
    }

    public NotMediaUploaderException(String message) {
        super(message);
    }
}

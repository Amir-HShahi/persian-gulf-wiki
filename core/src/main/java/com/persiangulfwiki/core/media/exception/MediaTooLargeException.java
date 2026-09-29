package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// 400, not 413: the request that carries the declared size is a few hundred bytes of JSON. The
// file itself never reaches the API, so there is no oversized request body to report.
public class MediaTooLargeException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "declared size exceeds the limit for this media type";

    public MediaTooLargeException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaTooLargeException(String message) {
        super(message);
    }
}

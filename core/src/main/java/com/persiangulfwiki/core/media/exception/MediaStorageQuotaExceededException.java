package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class MediaStorageQuotaExceededException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "media storage quota exceeded";

    public MediaStorageQuotaExceededException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaStorageQuotaExceededException(String message) {
        super(message);
    }
}

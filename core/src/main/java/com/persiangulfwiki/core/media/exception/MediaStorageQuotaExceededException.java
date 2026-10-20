package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class MediaStorageQuotaExceededException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "media storage quota exceeded";

    public MediaStorageQuotaExceededException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaStorageQuotaExceededException(String message) {
        super(message);
    }
}

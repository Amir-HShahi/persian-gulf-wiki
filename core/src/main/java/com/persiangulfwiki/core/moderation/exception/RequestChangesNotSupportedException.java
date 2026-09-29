package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// Media tasks take APPROVE/REJECT only: a contributor "changes" a media item's metadata by
// proposing a new version, which gets its own task.
public class RequestChangesNotSupportedException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "request changes is not a valid decision for this task";

    public RequestChangesNotSupportedException() {
        super(DEFAULT_MESSAGE);
    }

    public RequestChangesNotSupportedException(String message) {
        super(message);
    }
}

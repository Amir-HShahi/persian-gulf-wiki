package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.TooManyRequestsException;

// Carries a third constructor beyond the usual two because the Retry-After value is only known
// where the limit window is evaluated -- see TooManyRequestsException.
public class MetadataEditRateLimitedException extends TooManyRequestsException {

    private static final String DEFAULT_MESSAGE = "metadata edit rate limit reached";

    public MetadataEditRateLimitedException() {
        super(DEFAULT_MESSAGE);
    }

    public MetadataEditRateLimitedException(String message) {
        super(message);
    }

    public MetadataEditRateLimitedException(long retryAfterSeconds) {
        super(DEFAULT_MESSAGE, retryAfterSeconds);
    }
}

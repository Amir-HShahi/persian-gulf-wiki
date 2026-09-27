package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.TooManyRequestsException;

// Carries a third constructor beyond the usual two because the Retry-After value is only known
// where the limit window is evaluated -- see TooManyRequestsException.
public class MediaUploadRateLimitedException extends TooManyRequestsException {

    private static final String DEFAULT_MESSAGE = "media upload rate limit reached";

    public MediaUploadRateLimitedException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaUploadRateLimitedException(String message) {
        super(message);
    }

    public MediaUploadRateLimitedException(long retryAfterSeconds) {
        super(DEFAULT_MESSAGE, retryAfterSeconds);
    }
}

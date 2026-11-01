package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.TooManyRequestsException;

import java.io.Serial;

// Carries a third constructor beyond the usual two because the Retry-After value is only known
// where the limit window is evaluated -- see TooManyRequestsException.
public class AvatarUploadRateLimitedException extends TooManyRequestsException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "avatar upload rate limit reached";

    public AvatarUploadRateLimitedException() {
        super(DEFAULT_MESSAGE);
    }

    public AvatarUploadRateLimitedException(String message) {
        super(message);
    }

    public AvatarUploadRateLimitedException(long retryAfterSeconds) {
        super(DEFAULT_MESSAGE, retryAfterSeconds);
    }
}

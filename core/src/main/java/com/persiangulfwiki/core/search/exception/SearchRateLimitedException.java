package com.persiangulfwiki.core.search.exception;

import com.persiangulfwiki.core.common.exception.TooManyRequestsException;

import java.io.Serial;

// Carries a third constructor beyond the usual two because the Retry-After value is only known
// where the limit window is evaluated -- see TooManyRequestsException.
public class SearchRateLimitedException extends TooManyRequestsException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "search rate limit reached";

    public SearchRateLimitedException() {
        super(DEFAULT_MESSAGE);
    }

    public SearchRateLimitedException(String message) {
        super(message);
    }

    public SearchRateLimitedException(long retryAfterSeconds) {
        super(DEFAULT_MESSAGE, retryAfterSeconds);
    }
}

package com.persiangulfwiki.core.common.exception;

// Base for 429, added with the article gallery's upload rate limit (api-conventions.md lists
// 429 for rate limits). Carries retryAfterSeconds because the Retry-After header it becomes is
// only knowable where the limit was evaluated, not in GlobalExceptionHandler. Zero means
// "unknown", and the header is then omitted.
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(message, 0);
    }

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

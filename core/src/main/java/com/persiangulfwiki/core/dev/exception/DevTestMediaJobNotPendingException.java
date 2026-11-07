package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A fake worker result asked for on a minted item that has no parked job of its own still waiting
// for one: it was minted without withQueueJob, or a result has already been written onto its job.
public class DevTestMediaJobNotPendingException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "test media item has no pending minted job";

    public DevTestMediaJobNotPendingException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestMediaJobNotPendingException(String message) {
        super(message);
    }
}

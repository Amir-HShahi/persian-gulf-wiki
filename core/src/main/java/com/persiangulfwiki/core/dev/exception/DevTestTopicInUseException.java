package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A minted topic still tags a revision, so deleting it would mean untagging that revision or
// failing on the foreign key.
public class DevTestTopicInUseException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "test topic still tags a revision";

    public DevTestTopicInUseException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestTopicInUseException(String message) {
        super(message);
    }
}

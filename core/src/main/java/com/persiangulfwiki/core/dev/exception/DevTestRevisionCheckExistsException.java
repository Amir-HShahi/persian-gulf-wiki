package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// The revision already has a check of that type -- a revision holds one per type, and a real
// submit's row is never replaced by a fixture.
public class DevTestRevisionCheckExistsException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "revision already has a check of this type";

    public DevTestRevisionCheckExistsException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestRevisionCheckExistsException(String message) {
        super(message);
    }
}

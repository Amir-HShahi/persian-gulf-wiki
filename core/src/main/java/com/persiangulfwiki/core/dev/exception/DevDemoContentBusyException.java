package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DevDemoContentBusyException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "demo content is already being seeded or wiped";

    public DevDemoContentBusyException() {
        super(DEFAULT_MESSAGE);
    }

    public DevDemoContentBusyException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidDevTestMarineRequestException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid dev test marine request";

    public InvalidDevTestMarineRequestException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidDevTestMarineRequestException(String message) {
        super(message);
    }
}

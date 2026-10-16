package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidDashboardRangeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "unknown dashboard range";

    public InvalidDashboardRangeException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidDashboardRangeException(String message) {
        super(message);
    }
}

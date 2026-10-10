package com.persiangulfwiki.core.marinedata.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidMarineSeriesRangeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid marine series range";

    public InvalidMarineSeriesRangeException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidMarineSeriesRangeException(String message) {
        super(message);
    }
}

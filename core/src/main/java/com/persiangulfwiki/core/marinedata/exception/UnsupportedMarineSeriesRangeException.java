package com.persiangulfwiki.core.marinedata.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class UnsupportedMarineSeriesRangeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "series range not supported for this metric";

    public UnsupportedMarineSeriesRangeException() {
        super(DEFAULT_MESSAGE);
    }

    public UnsupportedMarineSeriesRangeException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.marinedata.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class MarineMetricNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "marine metric not found";

    public MarineMetricNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public MarineMetricNotFoundException(String message) {
        super(message);
    }
}

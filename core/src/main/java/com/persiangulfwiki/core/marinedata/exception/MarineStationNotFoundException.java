package com.persiangulfwiki.core.marinedata.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class MarineStationNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "marine station not found";

    public MarineStationNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public MarineStationNotFoundException(String message) {
        super(message);
    }
}

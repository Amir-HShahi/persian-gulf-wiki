package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class ApplicationNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "expert reviewer application not found";

    public ApplicationNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ApplicationNotFoundException(String message) {
        super(message);
    }
}

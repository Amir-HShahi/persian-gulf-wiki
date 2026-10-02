package com.persiangulfwiki.core.label.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class LabelNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "label not found";

    public LabelNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public LabelNotFoundException(String message) {
        super(message);
    }
}

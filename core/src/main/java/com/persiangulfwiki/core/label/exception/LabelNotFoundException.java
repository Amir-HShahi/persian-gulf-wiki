package com.persiangulfwiki.core.label.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class LabelNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "label not found";

    public LabelNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public LabelNotFoundException(String message) {
        super(message);
    }
}

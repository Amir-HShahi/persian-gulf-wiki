package com.persiangulfwiki.core.label.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidLabelReferentException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "label referent must be GROUP, ATTRIBUTE, OPTION or KIND with a matching id";

    public InvalidLabelReferentException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidLabelReferentException(String message) {
        super(message);
    }
}

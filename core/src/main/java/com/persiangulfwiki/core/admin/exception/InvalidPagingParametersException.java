package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidPagingParametersException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidPagingParametersException(String message) {
        super(message);
    }
}

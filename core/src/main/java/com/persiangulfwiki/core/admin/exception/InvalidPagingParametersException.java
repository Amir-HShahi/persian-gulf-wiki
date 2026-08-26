package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class InvalidPagingParametersException extends BadRequestException {

    public InvalidPagingParametersException(String message) {
        super(message);
    }
}

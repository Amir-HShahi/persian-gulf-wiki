package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class InvalidApplicationStatusException extends BadRequestException {

    public InvalidApplicationStatusException(String message) {
        super(message);
    }
}

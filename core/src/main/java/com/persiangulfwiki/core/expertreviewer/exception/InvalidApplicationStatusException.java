package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidApplicationStatusException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidApplicationStatusException(String message) {
        super(message);
    }
}

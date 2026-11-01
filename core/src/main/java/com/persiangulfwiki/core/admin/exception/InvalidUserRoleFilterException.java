package com.persiangulfwiki.core.admin.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidUserRoleFilterException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid user role filter";

    public InvalidUserRoleFilterException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidUserRoleFilterException(String message) {
        super(message);
    }
}

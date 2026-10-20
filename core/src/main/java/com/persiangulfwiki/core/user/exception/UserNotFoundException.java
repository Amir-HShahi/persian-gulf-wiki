package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class UserNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "user not found";

    public UserNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class UserNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "user not found";

    public UserNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}

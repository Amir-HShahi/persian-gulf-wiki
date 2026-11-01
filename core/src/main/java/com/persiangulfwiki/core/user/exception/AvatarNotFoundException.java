package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class AvatarNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "no such avatar upload for this user";

    public AvatarNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AvatarNotFoundException(String message) {
        super(message);
    }
}

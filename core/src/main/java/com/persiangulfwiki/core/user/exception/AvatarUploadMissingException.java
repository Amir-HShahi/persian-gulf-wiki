package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class AvatarUploadMissingException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "avatar upload missing or not matching its reservation";

    public AvatarUploadMissingException() {
        super(DEFAULT_MESSAGE);
    }

    public AvatarUploadMissingException(String message) {
        super(message);
    }
}

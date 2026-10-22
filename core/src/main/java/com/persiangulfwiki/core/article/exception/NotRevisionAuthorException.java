package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

import java.io.Serial;

public class NotRevisionAuthorException extends ForbiddenException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "caller is not the author of this revision";

    public NotRevisionAuthorException() {
        super(DEFAULT_MESSAGE);
    }

    public NotRevisionAuthorException(String message) {
        super(message);
    }
}

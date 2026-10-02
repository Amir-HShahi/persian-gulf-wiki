package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class TranslationNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "translation not found";

    public TranslationNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public TranslationNotFoundException(String message) {
        super(message);
    }
}

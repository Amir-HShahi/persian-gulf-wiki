package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class TranslationNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "translation not found";

    public TranslationNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public TranslationNotFoundException(String message) {
        super(message);
    }
}

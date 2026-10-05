package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// The article a moderator picked as a subject's main article cannot be it: it does not exist, is
// about another subject, or has no approved translation. Reported against the request's `articleId`
// field with the message key, resolved in the reader's language by GlobalExceptionHandler.
public class InvalidMainArticleException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "article cannot be the subject's main article";
    private static final String DEFAULT_MESSAGE_KEY = "validation.mainArticle.notFound";

    private final String messageKey;

    public InvalidMainArticleException() {
        super(DEFAULT_MESSAGE);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidMainArticleException(String message) {
        super(message);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidMainArticleException(String message, String messageKey) {
        super(message);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}

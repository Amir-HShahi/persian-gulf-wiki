package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

// Also what a caller who may not see a pending item gets -- deliberately indistinguishable
// from an item that does not exist, same as ArticleNotFoundException for an unpublished article.
public class MediaNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "media not found";

    public MediaNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaNotFoundException(String message) {
        super(message);
    }
}

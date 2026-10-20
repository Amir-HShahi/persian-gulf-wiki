package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A metadata edit on an item whose file never arrived (UPLOADING), failed, or was rejected or
// hidden: none of those can ever show the edit, so it is refused rather than queued for review.
public class MediaNotEditableException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "media item does not accept metadata edits in its current state";

    public MediaNotEditableException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaNotEditableException(String message) {
        super(message);
    }
}

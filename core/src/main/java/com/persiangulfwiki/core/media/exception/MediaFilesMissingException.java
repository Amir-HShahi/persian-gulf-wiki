package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// A READY item whose processed files are not all in storage any more. Approving it would publish
// dead URLs, so the approval is refused and rolled back; the item stays reviewable (a moderator
// can still reject it). Not a client mistake, so the handler logs it.
public class MediaFilesMissingException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "media item's processed files are missing from storage";

    public MediaFilesMissingException() {
        super(DEFAULT_MESSAGE);
    }

    public MediaFilesMissingException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

// No such item, or one the caller may not see. The two are the same answer on purpose, so the
// link field cannot be used to probe for items that are not public.
public class PanoramaLinkTargetNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "hotspot target not found";

    public PanoramaLinkTargetNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public PanoramaLinkTargetNotFoundException(String message) {
        super(message);
    }
}

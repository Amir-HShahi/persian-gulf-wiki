package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class SelfPanoramaLinkException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "a panorama may not link to itself";

    public SelfPanoramaLinkException() {
        super(DEFAULT_MESSAGE);
    }

    public SelfPanoramaLinkException(String message) {
        super(message);
    }
}

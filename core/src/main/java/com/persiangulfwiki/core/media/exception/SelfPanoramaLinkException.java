package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class SelfPanoramaLinkException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "a panorama may not link to itself";

    public SelfPanoramaLinkException() {
        super(DEFAULT_MESSAGE);
    }

    public SelfPanoramaLinkException(String message) {
        super(message);
    }
}

package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// Only raised for a target the caller may see; one they may not see is a 404
// (PanoramaLinkTargetNotFoundException) whatever its type, so the type check cannot be used to
// learn anything about it.
public class PanoramaLinkTargetNotPanoramaException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "hotspot target is not a 360 panorama";

    public PanoramaLinkTargetNotPanoramaException() {
        super(DEFAULT_MESSAGE);
    }

    public PanoramaLinkTargetNotPanoramaException(String message) {
        super(message);
    }
}

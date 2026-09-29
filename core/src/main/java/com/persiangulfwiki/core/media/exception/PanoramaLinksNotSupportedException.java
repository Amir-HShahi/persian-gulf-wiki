package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// Hotspots sent for an item that is not a PANORAMA_360: there is no sphere to place them on.
public class PanoramaLinksNotSupportedException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "only a 360 panorama may have hotspots";

    public PanoramaLinksNotSupportedException() {
        super(DEFAULT_MESSAGE);
    }

    public PanoramaLinksNotSupportedException(String message) {
        super(message);
    }
}

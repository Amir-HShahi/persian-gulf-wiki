package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// shotAtOffset without shotAt, or headingRef without headingDeg: each qualifies a value that
// is not there. A cross-field rule, so the service owns it rather than an annotation.
public class IncompleteMediaMetadataException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "media metadata field given without the field it qualifies";

    public IncompleteMediaMetadataException() {
        super(DEFAULT_MESSAGE);
    }

    public IncompleteMediaMetadataException(String message) {
        super(message);
    }
}

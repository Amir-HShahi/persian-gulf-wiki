package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// Approving a metadata version that can no longer become current: the item's current version
// is newer, or another decision closed this one while the approval waited for the item's lock.
// The current version only ever moves forward -- see MediaModerationService.
public class MetadataVersionSupersededException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "metadata version was superseded";

    public MetadataVersionSupersededException() {
        super(DEFAULT_MESSAGE);
    }

    public MetadataVersionSupersededException(String message) {
        super(message);
    }
}

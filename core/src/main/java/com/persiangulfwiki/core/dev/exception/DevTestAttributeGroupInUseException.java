package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// A minted group still holds a definition that wasn't minted (an admin moved a real one into it),
// so deleting the group would mean deleting real data or failing on the foreign key.
public class DevTestAttributeGroupInUseException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "test attribute group still holds a non-test definition";

    public DevTestAttributeGroupInUseException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestAttributeGroupInUseException(String message) {
        super(message);
    }
}

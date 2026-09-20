package com.persiangulfwiki.core.dev.dto;

import java.util.UUID;

// Both fields optional; a bare `{}` mints a source with a generated title and no creating
// account. createdByUserId is settable so a test can attribute a fixture source to a user it
// just minted, without going through the authenticated create path to do it.
public record DevTestSourceRequest(String title, UUID createdByUserId) {
}

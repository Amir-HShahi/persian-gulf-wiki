package com.persiangulfwiki.core.media.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// Which north a heading is measured from. Phones report either depending on sensor and
// settings; the difference in the Gulf is a few degrees, which is enough to matter for a
// panorama's initial view direction.
@Schema(description = HeadingReference.VALUES_DOC)
public enum HeadingReference {
    TRUE,
    MAGNETIC;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `TRUE`: measured from true (geographic) north.
            - `MAGNETIC`: measured from magnetic north. In the Gulf the two differ by a few degrees.""";
}

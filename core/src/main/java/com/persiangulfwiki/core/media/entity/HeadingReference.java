package com.persiangulfwiki.core.media.entity;

// Which north a heading is measured from. Phones report either depending on sensor and
// settings; the difference in the Gulf is a few degrees, which is enough to matter for a
// panorama's initial view direction.
public enum HeadingReference {
    TRUE,
    MAGNETIC
}

package com.persiangulfwiki.core.media.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// Mirrors ck_article_media_type (V18) exactly -- a new value means a migration alongside it.
// Not named MediaType: that would shadow org.springframework.http.MediaType in every class
// that also deals with HTTP content types, which is most of the ones that use this.
@Schema(description = MediaKind.VALUES_DOC)
public enum MediaKind {
    IMAGE,
    VIDEO,
    PANORAMA_360;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `IMAGE`: a photo.
            - `VIDEO`: a video.
            - `PANORAMA_360`: a 360° equirectangular image, twice as wide as it is tall.""";
}

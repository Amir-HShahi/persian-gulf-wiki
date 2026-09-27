package com.persiangulfwiki.core.media.entity;

// Mirrors ck_article_media_type (V18) exactly -- a new value means a migration alongside it.
// Not named MediaType: that would shadow org.springframework.http.MediaType in every class
// that also deals with HTTP content types, which is most of the ones that use this.
public enum MediaKind {
    IMAGE,
    VIDEO,
    PANORAMA_360
}

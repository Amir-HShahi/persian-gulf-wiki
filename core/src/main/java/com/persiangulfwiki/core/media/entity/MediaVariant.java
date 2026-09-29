package com.persiangulfwiki.core.media.entity;

// One processed rendition of a media item, as the pipeline reports it (Phase 2). Stored as an
// element of article_media.variants (jsonb), never as its own row -- see V18.
//
// size is the rendition's label ("placeholder", "640", "2k", "hls-720p", ...), format its
// encoding ("jpeg", "webp", "ktx2", "hls"), key its object key in the media bucket. width and
// height are pixels; bytes is the stored object's size.
public record MediaVariant(String size, String format, String key, long bytes, int width, int height) {
}

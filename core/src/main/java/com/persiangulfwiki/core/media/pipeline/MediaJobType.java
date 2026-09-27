package com.persiangulfwiki.core.media.pipeline;

import com.persiangulfwiki.core.media.entity.MediaKind;

// The job types core puts on the submissions stream for gallery uploads, and that the worker
// echoes back in each result's type. wireName is the contract string the Go
// submission-pipeline's queue.JobType values must match (docs/MEDIA_PIPELINE.md). Renaming one on
// this side alone strands every job already queued under the old name.
public enum MediaJobType {
    IMAGE("media.image"),
    PANORAMA("media.panorama"),
    VIDEO("media.video");

    private final String wireName;

    MediaJobType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MediaJobType of(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> IMAGE;
            case PANORAMA_360 -> PANORAMA;
            case VIDEO -> VIDEO;
        };
    }
}

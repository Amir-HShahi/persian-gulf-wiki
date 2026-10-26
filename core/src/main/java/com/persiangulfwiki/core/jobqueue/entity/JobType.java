package com.persiangulfwiki.core.jobqueue.entity;

import java.util.Arrays;
import java.util.Optional;

// The job types core enqueues, by the name the worker's handlers are registered under
// (submission-pipeline/README.md). The worker echoes the name back in its result's `type`.
// A name the worker has no handler for is parked as failed, not refused by the database, so a
// typo here surfaces only as failed jobs -- change a wire name on both sides together.
public enum JobType {
    // A gallery IMAGE's variants, placeholder, verified hash and attribution.
    IMAGE("image"),
    // The same for a PANORAMA_360, with 2:1 enforced and 2k/4k/8k variants.
    PANORAMA("panorama"),
    // Text-fix suggestions on an article revision.
    NORMALIZE("normalize"),
    // Dead-link check of an article revision's external links.
    LINKS("links");

    private final String wireName;

    JobType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<JobType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }
}

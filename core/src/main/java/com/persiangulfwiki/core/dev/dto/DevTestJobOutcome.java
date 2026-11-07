package com.persiangulfwiki.core.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// The three ways a worker can finish a job, for POST /api/dev/test-media/{mediaId}/job-result.
@Schema(description = DevTestJobOutcome.VALUES_DOC)
public enum DevTestJobOutcome {
    OK,
    REJECTED,
    FAILED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `OK`: processed; the item becomes READY with two image renditions (whose files do not exist).
            - `REJECTED`: the file was refused, with `reasonCode`; the item becomes FAILED with that code.
            - `FAILED`: the worker gave up; the item becomes FAILED with `processing_error`.""";
}

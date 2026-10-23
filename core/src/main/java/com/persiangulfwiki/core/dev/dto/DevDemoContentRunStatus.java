package com.persiangulfwiki.core.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Where the latest demo-content fill stands. IDLE until the first fill, and again after a removal.
@Schema(description = DevDemoContentRunStatus.VALUES_DOC)
public enum DevDemoContentRunStatus {
    IDLE, RUNNING, SUCCEEDED, FAILED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `IDLE`: no fill has run yet, or the demo content was removed since.
            - `RUNNING`: a fill is in progress.
            - `SUCCEEDED`: the latest fill finished.
            - `FAILED`: the latest fill stopped with an error.""";
}

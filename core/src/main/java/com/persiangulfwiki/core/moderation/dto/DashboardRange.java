package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = DashboardRange.VALUES_DOC)
public enum DashboardRange {
    LAST_7_DAYS(7), LAST_30_DAYS(30);

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `LAST_7_DAYS`: the last 7 days in Tehran time, today included as the last day.
            - `LAST_30_DAYS`: the last 30 days in Tehran time, today included as the last day.""";

    private final int days;

    DashboardRange(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}

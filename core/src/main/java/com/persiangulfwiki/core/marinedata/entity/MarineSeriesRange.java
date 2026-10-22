package com.persiangulfwiki.core.marinedata.entity;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.temporal.ChronoUnit;

// day = 24 hourly points, week = 7 daily, month = 30 daily. The bucket unit doubles as the
// date_trunc field name in MarineMetricReadingRepository's series queries.
@Getter
@RequiredArgsConstructor
@Schema(description = MarineSeriesRange.VALUES_DOC)
public enum MarineSeriesRange {

    DAY(ChronoUnit.HOURS, 24),
    WEEK(ChronoUnit.DAYS, 7),
    MONTH(ChronoUnit.DAYS, 30);

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `DAY`: 24 points, one per hour.
            - `WEEK`: 7 points, one per day.
            - `MONTH`: 30 points, one per day.""";

    private final ChronoUnit bucketUnit;
    private final int bucketCount;

    public String bucketField() {
        return bucketUnit == ChronoUnit.HOURS ? "hour" : "day";
    }
}

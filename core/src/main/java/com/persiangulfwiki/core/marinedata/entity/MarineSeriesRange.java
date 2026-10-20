package com.persiangulfwiki.core.marinedata.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.temporal.ChronoUnit;

// day = 24 hourly points, week = 7 daily, month = 30 daily. The bucket unit doubles as the
// date_trunc field name in MarineMetricReadingRepository's series queries.
@Getter
@RequiredArgsConstructor
public enum MarineSeriesRange {

    DAY(ChronoUnit.HOURS, 24),
    WEEK(ChronoUnit.DAYS, 7),
    MONTH(ChronoUnit.DAYS, 30);

    private final ChronoUnit bucketUnit;
    private final int bucketCount;

    public String bucketField() {
        return bucketUnit == ChronoUnit.HOURS ? "hour" : "day";
    }
}

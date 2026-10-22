package com.persiangulfwiki.core.marinedata.entity;

// How a bucket of readings collapses to one chart point. MAX for stress indicators, where
// averaging would hide the day a reef actually crossed an alert level.
public enum MarineAggregation {
    AVG,
    MAX
}

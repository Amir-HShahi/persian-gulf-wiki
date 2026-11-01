package com.persiangulfwiki.core.moderation.repository;

// One row of a native GROUP BY over a local calendar day; day is ISO yyyy-MM-dd text, because a
// native date column does not reliably map to LocalDate through an interface projection.
public interface DayCount {

    String getDay();

    long getCount();
}

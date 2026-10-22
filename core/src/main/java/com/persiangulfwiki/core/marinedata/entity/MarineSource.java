package com.persiangulfwiki.core.marinedata.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// Attribution travels with every reading the API returns: Open-Meteo's free tier is CC BY 4.0
// and requires it, and NOAA's products are US-government public domain.
@Getter
@RequiredArgsConstructor
public enum MarineSource {

    OPEN_METEO_MARINE("Open-Meteo", "CC BY 4.0"),
    OPEN_METEO_AIR_QUALITY("Open-Meteo", "CC BY 4.0"),
    NOAA_ERDDAP("NOAA Coral Reef Watch", "Public domain");

    private final String sourceName;
    private final String sourceLicense;
}

package com.persiangulfwiki.core.marinedata.client;

import com.persiangulfwiki.core.marinedata.MarineDataProperties.Station;
import com.persiangulfwiki.core.marinedata.entity.MarineSource;

import java.time.Instant;
import java.util.List;

public interface MarineDataClient {

    MarineSource source();

    /**
     * Every non-missing value the provider returns for the look-back window, forecasts
     * included — dropping future hours is the caller's job. Throws on any transport or HTTP
     * error; never returns a partial result for a failed call.
     */
    List<ProviderReading> fetch(Station station, Instant now);
}

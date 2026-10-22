package com.persiangulfwiki.core.marinedata.client;

import com.persiangulfwiki.core.marinedata.entity.MarineMetric;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderReading(MarineMetric metric, Instant observedAt, BigDecimal value) {
}

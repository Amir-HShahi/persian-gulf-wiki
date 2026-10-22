package com.persiangulfwiki.core.marinedata.repository;

import com.persiangulfwiki.core.marinedata.entity.MarineSourceSync;
import com.persiangulfwiki.core.marinedata.entity.MarineSourceSyncId;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface MarineSourceSyncRepository extends JpaRepository<MarineSourceSync, MarineSourceSyncId> {

    long deleteByIdStationKey(String stationKey);

    long deleteByIdStationKeyStartingWithAndLastAttemptAtBefore(String stationKeyPrefix, Instant threshold);
}

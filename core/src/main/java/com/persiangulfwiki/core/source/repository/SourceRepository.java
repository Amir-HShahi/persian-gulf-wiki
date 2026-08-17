package com.persiangulfwiki.core.source.repository;

import com.persiangulfwiki.core.source.entity.Source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}

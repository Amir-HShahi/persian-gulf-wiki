package com.persiangulfwiki.core.media.repository;

import com.persiangulfwiki.core.media.entity.PanoramaLink;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PanoramaLinkRepository extends JpaRepository<PanoramaLink, UUID> {

    List<PanoramaLink> findByMetadataVersionId(UUID metadataVersionId);

    List<PanoramaLink> findByMetadataVersionIdIn(Collection<UUID> metadataVersionIds);
}

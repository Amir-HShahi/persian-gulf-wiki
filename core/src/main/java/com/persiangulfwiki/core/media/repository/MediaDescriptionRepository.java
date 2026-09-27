package com.persiangulfwiki.core.media.repository;

import com.persiangulfwiki.core.media.entity.MediaDescription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MediaDescriptionRepository extends JpaRepository<MediaDescription, UUID> {

    List<MediaDescription> findByMetadataVersionId(UUID metadataVersionId);

    List<MediaDescription> findByMetadataVersionIdIn(Collection<UUID> metadataVersionIds);

    List<MediaDescription> findByMetadataVersionIdInAndLanguage(Collection<UUID> metadataVersionIds, String language);
}

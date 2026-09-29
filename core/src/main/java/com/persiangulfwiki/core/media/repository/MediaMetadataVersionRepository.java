package com.persiangulfwiki.core.media.repository;

import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaMetadataVersionRepository extends JpaRepository<MediaMetadataVersion, UUID> {

    List<MediaMetadataVersion> findByMediaIdAndStatusOrderByVersionNumberAsc(UUID mediaId,
            MetadataVersionStatus status);

    Optional<MediaMetadataVersion> findFirstByMediaIdOrderByVersionNumberDesc(UUID mediaId);

    List<MediaMetadataVersion> findByMediaIdOrderByVersionNumberAsc(UUID mediaId);

    // A fresh read of the row, not of the persistence context: an already-loaded version keeps
    // the status it was loaded with even after another transaction changes it.
    boolean existsByIdAndStatus(UUID id, MetadataVersionStatus status);

    // Edits only: version 1 is the upload's own metadata and already counts toward the upload
    // pending cap (ArticleMediaRepository.countPendingByUploader).
    @Query("""
            select count(v) from MediaMetadataVersion v
            where v.submittedBy = :userId
              and v.status = com.persiangulfwiki.core.media.entity.MetadataVersionStatus.PENDING_REVIEW
              and v.versionNumber > 1
            """)
    long countPendingEditsBySubmitter(@Param("userId") UUID userId);
}

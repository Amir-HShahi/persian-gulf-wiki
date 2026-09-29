package com.persiangulfwiki.core.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

// A one-way hotspot from the panorama that owns metadataVersionId to toMediaId. Immutable:
// moving a hotspot is a new metadata version. A link to a target that is not publicly visible
// is kept and filtered at read time, never deleted -- see V18.
@Entity
@Table(name = "panorama_links")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PanoramaLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "metadata_version_id", nullable = false, updatable = false)
    private UUID metadataVersionId;

    @Column(name = "to_media_id", nullable = false, updatable = false)
    private UUID toMediaId;

    // Degrees clockwise from the panorama's own north, [0, 360).
    @Column(name = "yaw_deg", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal yawDeg;

    // Degrees above the horizon, [-90, 90].
    @Column(name = "pitch_deg", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal pitchDeg;

    @Column(length = 200, updatable = false)
    private @Nullable String label;
}

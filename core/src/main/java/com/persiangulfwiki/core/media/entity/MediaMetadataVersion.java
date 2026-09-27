package com.persiangulfwiki.core.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One moderated version of what is said about a media item. Same lifecycle shape as
// ArticleRevision: append-only history, created_at but no updated_at, and the only mutation
// after insert is the moderation outcome on status. Every metadata field is nullable -- see
// V18 for why an absent value is always preferred to a guessed one.
@Entity
@Table(name = "media_metadata_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class MediaMetadataVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetadataVersionStatus status;

    @Column(name = "shot_at")
    private @Nullable Instant shotAt;

    // "+03:30"-style offset, never inferred -- see V18.
    @Column(name = "shot_at_offset", length = 6)
    private @Nullable String shotAtOffset;

    // geography, not geometry: SqlTypes.GEOGRAPHY is what makes Hibernate Spatial bind and read
    // the column as PostGIS geography rather than handing a geometry to a geography column.
    // SRID 4326 must be set on the Point itself (JTS defaults to 0), same as Island.location.
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geography(Point,4326)")
    private @Nullable Point location;

    @Column(name = "altitude_m", precision = 7, scale = 1)
    private @Nullable BigDecimal altitudeM;

    @Column(name = "heading_deg", precision = 5, scale = 2)
    private @Nullable BigDecimal headingDeg;

    @Enumerated(EnumType.STRING)
    @Column(name = "heading_ref", length = 10)
    private @Nullable HeadingReference headingRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

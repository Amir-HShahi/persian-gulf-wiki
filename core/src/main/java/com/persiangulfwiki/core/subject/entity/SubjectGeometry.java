package com.persiangulfwiki.core.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.locationtech.jts.geom.Geometry;

import java.time.Instant;
import java.util.UUID;

// Extra geometries beyond the single one a subject's subtype row holds — an island's reef
// outline next to its centroid, a port's approach channel next to its berth. Many per
// subject, so unlike Island/Port/OilField/Species this has a surrogate id of its own rather
// than sharing the subject's, and the geometry column is untyped because the shape varies
// with what is being recorded.
@Entity
@Table(name = "subject_geometries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SubjectGeometry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @Column(nullable = false, columnDefinition = "geometry(Geometry,4326)")
    private Geometry geom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

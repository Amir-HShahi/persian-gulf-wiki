package com.persiangulfwiki.core.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.util.UUID;

// Shares its primary key with the subject it details, via @MapsId: subject_id is both the
// FK and the PK, so the pairing is 1:1 by construction rather than by convention.
//
// This is composition, not @Inheritance(JOINED) on Subject. JOINED would make Subject
// abstract-in-practice and force every subject read to resolve a discriminator and pick a
// subclass, which breaks the cases that matter here — a subject whose detail row hasn't
// been filled in yet, and a caller (e.g. Article.subjectId) that only needs the subject's
// identity and kind and shouldn't pay for a join to get it.
@Entity
@Table(name = "islands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "subjectId")
public class Island {

    @Id
    @Column(name = "subject_id")
    private UUID subjectId;

    @MapsId
    @OneToOne(optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Column(name = "area_km2", precision = 12, scale = 4)
    private BigDecimal areaKm2;

    // SRID 4326 is fixed in the column type by V15; SubjectService stamps it on every
    // geometry it parses, because JTS defaults a freshly-read WKT geometry to SRID 0 and
    // Postgres rejects the insert rather than silently coercing it.
    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;
}

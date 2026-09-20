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

import org.locationtech.jts.geom.Polygon;

import java.util.UUID;

// Shared-PK detail row for SubjectKind.OIL_FIELD — see Island for why this is composition
// rather than JPA inheritance. An oil field is an extent rather than a point, hence Polygon.
@Entity
@Table(name = "oil_fields")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "subjectId")
public class OilField {

    @Id
    @Column(name = "subject_id")
    private UUID subjectId;

    @MapsId
    @OneToOne(optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Column(columnDefinition = "geometry(Polygon,4326)")
    private Polygon area;
}

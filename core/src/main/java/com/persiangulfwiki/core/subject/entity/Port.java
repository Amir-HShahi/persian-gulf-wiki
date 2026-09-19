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

import java.util.UUID;

// Shared-PK detail row for SubjectKind.PORT — see Island for why this is composition rather
// than JPA inheritance.
@Entity
@Table(name = "ports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "subjectId")
public class Port {

    @Id
    @Column(name = "subject_id")
    private UUID subjectId;

    @MapsId
    @OneToOne(optional = false)
    @JoinColumn(name = "subject_id")
    private Subject subject;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;
}

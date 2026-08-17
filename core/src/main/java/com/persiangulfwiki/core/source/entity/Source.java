package com.persiangulfwiki.core.source.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// A citable work: what a measurement, claim or fact-provenance row points at to say where a
// number came from. Independent of any one subject — a single survey report is cited by many
// islands — and worth recording before anything cites it.
//
// Not an AuditableEntity: like Subject, the schema gives this a created_at and no
// updated_at.
@Entity
@Table(name = "sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column
    private String url;

    @Column
    private String publisher;

    // A date, not an Instant: publication is a calendar event with no meaningful time of day,
    // and a timestamp would invent a precision the citation doesn't have.
    @Column(name = "published_on")
    private LocalDate publishedOn;

    // Null once the creating account is deleted (ON DELETE SET NULL in V15) — a citation
    // outlives the contributor who entered it, since the works citing it are still standing.
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Set only by the dev-profile fixture endpoint; null on every row a real caller creates.
    @Column(name = "dev_marker", length = 40)
    private String devMarker;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

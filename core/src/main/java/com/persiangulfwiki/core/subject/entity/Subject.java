package com.persiangulfwiki.core.subject.entity;

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

import java.time.Instant;
import java.util.UUID;

// Deliberately not an AuditableEntity: the schema gives a subject a created_at and no
// updated_at, and a subject's own row never changes after insert — the mutable parts of a
// subject live in its subtype row, its measurements and its labels. Inheriting an
// updated_at that nothing ever advances would be a column that lies.
//
// Carries no name. Names are per-language and belong to the `labels` table; see the comment
// on the subjects table in V15 for why duplicating one here would be a second source of
// truth rather than a convenience.
@Entity
@Table(name = "subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubjectKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Set only by the dev-profile fixture endpoint; null on every row a real caller creates.
    // See DevTestSubjects for what depends on that distinction.
    @Column(name = "dev_marker", length = 40)
    private String devMarker;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// Deliberately not an AuditableEntity: the schema gives a subject a created_at and no
// updated_at. The only column that ever changes after insert is deleted_at, which records its
// own moment; the mutable parts of a subject live in its subtype row, its facts and its labels.
// Inheriting an updated_at that nothing else advances would be a column that lies.
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

    // Set by a soft delete or a merge (V29); a merged subject also has a SubjectRedirect. Hidden
    // from every public read while set.
    @Column(name = "deleted_at")
    private @Nullable Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// One proposed state of a subject: names, aliases, facts, geometry and main image together, as a
// jsonb payload. subjectId is null only on a proposal that has not been approved yet; approval
// fills it in. A DRAFT/CHANGES_REQUESTED row is edited in place, like ArticleRevision.
@Entity
@Table(name = "subject_revisions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SubjectRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Setter
    @Column(name = "subject_id")
    private @Nullable UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private SubjectKind kind;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "base_revision_id", updatable = false)
    private @Nullable UUID baseRevisionId;

    // JSON text, converted at the service boundary -- the same mapping decision as
    // ArticleRevision.body, for the same reason.
    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Setter
    @Column
    private @Nullable String summary;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private SubjectRevisionStatus status;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Set only by the dev-profile fixture endpoint; see DevTestFixtures.
    @Column(name = "dev_marker", length = 40, updatable = false)
    private @Nullable String devMarker;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

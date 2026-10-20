package com.persiangulfwiki.core.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// The pointer to the revision readers see, moved by each approval. A row of its own so the
// subjects row stays insert-only; a subject without one has never had a revision approved.
@Entity
@Table(name = "subject_live_revisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "subjectId")
public class SubjectLiveRevision {

    @Id
    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;
}

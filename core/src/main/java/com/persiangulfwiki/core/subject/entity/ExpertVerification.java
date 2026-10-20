package com.persiangulfwiki.core.subject.entity;

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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// An expert's mark that one published subject revision is correct. Insert-only: there is no
// un-mark, and a newer revision gets marks of its own rather than inheriting these.
@Entity
@Table(name = "expert_verifications")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ExpertVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subject_revision_id", nullable = false, updatable = false)
    private UUID subjectRevisionId;

    @Column(name = "expert_id", nullable = false, updatable = false)
    private UUID expertId;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    // Set only by the dev-profile fixture endpoint; see DevTestFixtures.
    @Column(name = "dev_marker", length = 40, updatable = false)
    private @Nullable String devMarker;

    @PrePersist
    void onCreate() {
        if (verifiedAt == null) {
            verifiedAt = Instant.now();
        }
    }
}

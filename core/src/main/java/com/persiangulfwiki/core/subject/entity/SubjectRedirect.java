package com.persiangulfwiki.core.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// A merged-away subject and the subject it was merged into (V29). Always one hop: merging a target
// away repoints its incoming redirects, so `targetId` is never itself a merged subject.
@Entity
@Table(name = "subject_redirects")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "sourceId")
public class SubjectRedirect {

    @Id
    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "merged_at", nullable = false, updatable = false)
    private Instant mergedAt;

    @PrePersist
    void onCreate() {
        if (mergedAt == null) {
            mergedAt = Instant.now();
        }
    }
}

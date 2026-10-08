package com.persiangulfwiki.core.topic.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

// What an article is about (marine life, history, ...). Its name lives in `labels`; `slug` is a
// permanent code the frontend keys on, `icon` a Lucide icon name (see V30).
@Entity
@Table(name = "topics")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "slug")
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 60)
    private String slug;

    @Setter
    @Column(nullable = false, length = 64)
    private String icon;

    // Upper-case #RRGGBB; ck_topics_color rejects any other form.
    @Setter
    @Column(nullable = false, length = 7)
    private String color;

    @Setter
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Setter
    @Column(name = "is_archived", nullable = false)
    private boolean isArchived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Set only by the dev-profile fixture endpoint; see DevTestFixtures.
    @Column(name = "dev_marker", length = 40, updatable = false)
    private @Nullable String devMarker;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

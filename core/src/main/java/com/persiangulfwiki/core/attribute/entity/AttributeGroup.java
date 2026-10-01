package com.persiangulfwiki.core.attribute.entity;

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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// A heading the infobox and the edit form group fields under (geography, conservation, ...).
// Its name lives in `labels`; `key` is an immutable code (see V22).
@Entity
@Table(name = "attribute_groups")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "key")
public class AttributeGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 60)
    private String key;

    @Setter
    @Column(nullable = false)
    private int sort;

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

package com.persiangulfwiki.core.attribute.entity;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// One infobox/filter field. `key` is an immutable code (see V22); everything else is
// admin-editable. The cross-column rules (ref_kind iff SUBJECT_REF, unit and bounds only on
// NUMBER, filterable only on countable types) are CHECKs in V22, validated up front by
// ValidAttributeTypeSpec so a violation is a 400 rather than a constraint error.
@Entity
@Table(name = "attribute_definitions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "key")
public class AttributeDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 60)
    private String key;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private AttributeValueType valueType;

    // A unit code the frontend localizes (e.g. "cm", "km2"), not display text.
    @Setter
    @Column(length = 20)
    private @Nullable String unit;

    @Setter
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Setter
    @Column(nullable = false)
    private int sort;

    @Setter
    @Column(name = "is_multi", nullable = false)
    private boolean isMulti;

    @Setter
    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    @Setter
    @Column(name = "is_infobox", nullable = false)
    private boolean isInfobox;

    @Setter
    @Column(name = "is_filterable", nullable = false)
    private boolean isFilterable;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "ref_kind", length = 30)
    private @Nullable SubjectKind refKind;

    @Setter
    @Column(name = "min_value")
    private @Nullable BigDecimal minValue;

    @Setter
    @Column(name = "max_value")
    private @Nullable BigDecimal maxValue;

    @Setter
    @Column(name = "is_deprecated", nullable = false)
    private boolean isDeprecated;

    // The kinds this field applies to; a field such as `province` is shared across several.
    @ElementCollection
    @CollectionTable(name = "attribute_definition_kinds", joinColumns = @JoinColumn(name = "attribute_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    @Builder.Default
    private Set<SubjectKind> kinds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Set only by the dev-profile fixture endpoint; see DevTestFixtures.
    @Column(name = "dev_marker", length = 40, updatable = false)
    private @Nullable String devMarker;

    public Set<SubjectKind> getKinds() {
        return Set.copyOf(kinds);
    }

    public void replaceKinds(Set<SubjectKind> newKinds) {
        kinds.clear();
        kinds.addAll(newKinds);
    }

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

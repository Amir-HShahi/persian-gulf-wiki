package com.persiangulfwiki.core.attribute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

// One allowed value of an ENUM field. `code` is immutable (see V22); retiring a value means
// is_deprecated, never a delete, so facts already pointing at it stay readable.
@Entity
@Table(name = "attribute_options")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"attributeId", "code"})
public class AttributeOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Column(nullable = false, updatable = false, length = 60)
    private String code;

    @Setter
    @Column(nullable = false)
    private int sort;

    @Setter
    @Column(name = "is_deprecated", nullable = false)
    private boolean isDeprecated;
}

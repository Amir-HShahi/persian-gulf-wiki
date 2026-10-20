package com.persiangulfwiki.core.subject.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// One value of one field of the live revision, with its code resolved to an id -- the read model
// facets and the infobox query. Written only by approval, which deletes and re-inserts a subject's
// rows together, so a row is never updated. Exactly one value column is set
// (ck_subject_facts_exactly_one_value, V24).
@Entity
@Table(name = "subject_facts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SubjectFact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(nullable = false, updatable = false)
    private int sort;

    @Column(name = "value_number", updatable = false)
    private @Nullable BigDecimal valueNumber;

    @Column(name = "value_option_id", updatable = false)
    private @Nullable UUID valueOptionId;

    @Column(name = "value_ref_subject_id", updatable = false)
    private @Nullable UUID valueRefSubjectId;

    @Column(name = "value_bool", updatable = false)
    private @Nullable Boolean valueBool;

    @Column(name = "value_date", updatable = false)
    private @Nullable LocalDate valueDate;

    @Column(name = "value_text", updatable = false)
    private @Nullable String valueText;

    // {"fa": ..., "en": ..., "ar": ...} as JSON text -- see ArticleRevision.body for why String.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_localized", columnDefinition = "jsonb", updatable = false)
    private @Nullable String valueLocalized;

    @Column(name = "as_of", updatable = false)
    private @Nullable LocalDate asOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private FactRank rank;

    @Column(name = "source_id", updatable = false)
    private @Nullable UUID sourceId;
}

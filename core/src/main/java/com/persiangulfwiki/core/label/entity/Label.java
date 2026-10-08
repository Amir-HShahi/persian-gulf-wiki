package com.persiangulfwiki.core.label.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// One human-readable name of one referent in one language. Exactly one of the six referent
// columns is set (V22's ck_labels_exactly_one_referent); they are plain ids rather than
// associations because nothing reads a label through its referent's entity, only the other way.
// Only `value` ever changes after insert.
@Entity
@Table(name = "labels")
@Getter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Label extends AuditableEntity {

    @Column(name = "group_id", updatable = false)
    private @Nullable UUID groupId;

    @Column(name = "attribute_id", updatable = false)
    private @Nullable UUID attributeId;

    @Column(name = "option_id", updatable = false)
    private @Nullable UUID optionId;

    @Column(name = "subject_id", updatable = false)
    private @Nullable UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 30)
    private @Nullable SubjectKind kind;

    @Column(name = "topic_id", updatable = false)
    private @Nullable UUID topicId;

    // "fa", "en" or "ar" — see config/SupportedLocales.
    @Column(nullable = false, updatable = false, length = 2)
    private String language;

    @Setter
    @Column(nullable = false, length = 200)
    private String value;
}

package com.persiangulfwiki.core.subject.entity;

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

import java.util.UUID;

// Another name the live revision gives a subject in one language. Replaced as a whole by each
// approval, so a row is never updated.
@Entity
@Table(name = "subject_aliases")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = {"subjectId", "language", "value"})
public class SubjectAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    // "fa", "en" or "ar" -- see config/SupportedLocales.
    @Column(nullable = false, updatable = false, length = 2)
    private String language;

    @Column(nullable = false, updatable = false, length = 200)
    private String value;
}

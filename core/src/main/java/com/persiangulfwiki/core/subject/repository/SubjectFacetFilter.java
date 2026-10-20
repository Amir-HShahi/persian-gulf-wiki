package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

// What the facet listing filters subjects by, already checked against the field vocabulary:
// subjects of any of `kinds` (none means every kind) that satisfy every condition. One condition
// per field, so OR within a field (its values) and AND across fields.
public record SubjectFacetFilter(Set<SubjectKind> kinds, List<Condition> conditions) {

    public SubjectFacetFilter {
        kinds = Set.copyOf(kinds);
        conditions = List.copyOf(conditions);
    }

    // A field's facet counts what each of its options would add, so they are computed with every
    // other field's condition applied but not its own.
    public SubjectFacetFilter without(String key) {
        return new SubjectFacetFilter(kinds, conditions.stream().filter(condition -> !condition.key().equals(key))
                .toList());
    }

    public SubjectFacetFilter withAnyKind() {
        return new SubjectFacetFilter(Set.of(), conditions);
    }

    public SubjectFacetFilter with(Condition condition) {
        return new SubjectFacetFilter(kinds, Stream.concat(conditions.stream(), Stream.of(condition)).toList());
    }

    public sealed interface Condition permits OptionCondition, ReferenceCondition, TrueCondition, Has360TourCondition {

        String key();
    }

    // The subject has a PREFERRED value of the field that is one of the options.
    public record OptionCondition(String key, UUID attributeId, Set<UUID> optionIds) implements Condition {

        public OptionCondition {
            optionIds = Set.copyOf(optionIds);
        }
    }

    // The subject has a PREFERRED value of the field that refers to one of the subjects.
    public record ReferenceCondition(String key, UUID attributeId, Set<UUID> subjectIds) implements Condition {

        public ReferenceCondition {
            subjectIds = Set.copyOf(subjectIds);
        }
    }

    // The subject has a PREFERRED true value of the BOOL field.
    public record TrueCondition(String key, UUID attributeId) implements Condition {
    }

    // The derived has_360_tour: the subject's main article has a public 360° panorama.
    public record Has360TourCondition(String key) implements Condition {
    }
}

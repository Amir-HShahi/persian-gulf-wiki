package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// What a merge publishes on the target: the target's current payload with the source's folded in
// (approved by the user, see the subject-infobox plan's phase 7).
//
// - Names: the target's, each language the target lacks filled from the source.
// - Aliases: the target's, then the source's, then every source name that didn't become a target name
//   -- so the duplicate lookup still finds the subject by its old names.
// - Facts: a field only one side has is taken whole. A field both have keeps the target's values and
//   adds the source's that differ; on a single-valued field whose target value is PREFERRED those come
//   in as NORMAL, so the target's value stays the one shown.
// - Geometry: the target's, else the source's. Main image: chosen by the caller.
//
// References to the source are left as they are: the merge writes its redirect first, so validation
// rewrites them to the target and drops the resulting self-references (SubjectReferenceRemapper).
final class SubjectPayloadMerger {

    private SubjectPayloadMerger() {
    }

    static SubjectPayloadRequest merge(SubjectPayloadRequest target, SubjectPayloadRequest source,
            Set<String> multiValuedKeys, @Nullable UUID mainImageMediaId) {
        LocalizedTextRequest targetNames = Objects.requireNonNullElse(target.names(),
                new LocalizedTextRequest(null, null, null));
        LocalizedTextRequest sourceNames = Objects.requireNonNullElse(source.names(),
                new LocalizedTextRequest(null, null, null));
        LocalizedTextRequest names = new LocalizedTextRequest(
                firstPresent(targetNames.fa(), sourceNames.fa()),
                firstPresent(targetNames.en(), sourceNames.en()),
                firstPresent(targetNames.ar(), sourceNames.ar()));

        SubjectAliasesRequest targetAliases = aliasesOf(target);
        SubjectAliasesRequest sourceAliases = aliasesOf(source);
        SubjectAliasesRequest aliases = new SubjectAliasesRequest(
                mergeAliases(names.fa(), targetAliases.fa(), sourceAliases.fa(), sourceNames.fa()),
                mergeAliases(names.en(), targetAliases.en(), sourceAliases.en(), sourceNames.en()),
                mergeAliases(names.ar(), targetAliases.ar(), sourceAliases.ar(), sourceNames.ar()));

        Map<String, List<SubjectFactValueRequest>> targetFacts = factsByKey(target);
        Map<String, List<SubjectFactValueRequest>> sourceFacts = factsByKey(source);
        Set<String> keys = new LinkedHashSet<>(targetFacts.keySet());
        keys.addAll(sourceFacts.keySet());
        List<SubjectFactRequest> facts = new ArrayList<>();
        for (String key : keys) {
            List<SubjectFactValueRequest> targetValues = targetFacts.getOrDefault(key, List.of());
            List<SubjectFactValueRequest> sourceValues = sourceFacts.getOrDefault(key, List.of());
            facts.add(new SubjectFactRequest(key, mergeValues(targetValues, sourceValues,
                    multiValuedKeys.contains(key))));
        }

        return new SubjectPayloadRequest(names, aliases, facts, firstPresent(target.geometry(), source.geometry()),
                mainImageMediaId);
    }

    private static List<SubjectFactValueRequest> mergeValues(List<SubjectFactValueRequest> targetValues,
            List<SubjectFactValueRequest> sourceValues, boolean isMulti) {
        if (targetValues.isEmpty()) {
            return sourceValues;
        }
        // A single-valued field takes at most one PREFERRED value, and the target's wins.
        boolean isDemoting = !isMulti && targetValues.stream().anyMatch(value -> rankOf(value) == FactRank.PREFERRED);
        List<SubjectFactValueRequest> merged = new ArrayList<>(targetValues);
        Set<String> seen = new HashSet<>();
        targetValues.forEach(value -> seen.add(identity(value)));
        for (SubjectFactValueRequest value : sourceValues) {
            if (seen.add(identity(value))) {
                merged.add(isDemoting ? withRank(value, FactRank.NORMAL) : value);
            }
        }
        return merged;
    }

    // Two values saying the same thing, whatever their rank or source. An option or a referenced
    // subject is the same value at any date (the validator refuses it twice in one field); anything
    // else is a different statement when it held at a different date, e.g. two census years.
    private static String identity(SubjectFactValueRequest value) {
        if (value.option() != null) {
            return "option:" + value.option();
        }
        if (value.subjectId() != null) {
            return "subject:" + value.subjectId();
        }
        Object content;
        if (value.number() != null) {
            content = value.number().stripTrailingZeros().toPlainString();
        } else if (value.localized() != null) {
            content = value.localized();
        } else if (value.bool() != null) {
            content = value.bool();
        } else if (value.date() != null) {
            content = value.date();
        } else {
            content = value.text();
        }
        return "value:" + content + "@" + value.asOf();
    }

    private static List<String> mergeAliases(@Nullable String mergedName, List<String> targetAliases,
            List<String> sourceAliases, @Nullable String sourceName) {
        Set<String> aliases = new LinkedHashSet<>(targetAliases);
        aliases.addAll(sourceAliases);
        if (sourceName != null) {
            aliases.add(sourceName);
        }
        aliases.removeIf(alias -> alias == null || alias.equals(mergedName));
        return List.copyOf(aliases);
    }

    private static SubjectAliasesRequest aliasesOf(SubjectPayloadRequest payload) {
        SubjectAliasesRequest aliases = payload.aliases();
        return aliases == null ? new SubjectAliasesRequest(List.of(), List.of(), List.of())
                : new SubjectAliasesRequest(orEmpty(aliases.fa()), orEmpty(aliases.en()), orEmpty(aliases.ar()));
    }

    private static List<String> orEmpty(@Nullable List<String> list) {
        return Objects.requireNonNullElse(list, List.of());
    }

    private static Map<String, List<SubjectFactValueRequest>> factsByKey(SubjectPayloadRequest payload) {
        Map<String, List<SubjectFactValueRequest>> byKey = new LinkedHashMap<>();
        Objects.requireNonNullElse(payload.facts(), List.<SubjectFactRequest>of()).stream()
                .filter(fact -> fact != null && fact.attribute() != null && fact.values() != null)
                .forEach(fact -> byKey.putIfAbsent(fact.attribute(),
                        fact.values().stream().filter(Objects::nonNull).toList()));
        return byKey;
    }

    private static @Nullable String firstPresent(@Nullable String preferred, @Nullable String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static FactRank rankOf(SubjectFactValueRequest value) {
        return Objects.requireNonNullElse(value.rank(), FactRank.PREFERRED);
    }

    private static SubjectFactValueRequest withRank(SubjectFactValueRequest value, FactRank rank) {
        return new SubjectFactValueRequest(value.number(), value.option(), value.subjectId(), value.bool(),
                value.date(), value.text(), value.localized(), value.asOf(), value.sourceId(), rank);
    }
}

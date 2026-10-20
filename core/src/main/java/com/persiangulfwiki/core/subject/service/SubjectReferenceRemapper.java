package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Rewrites a payload's SUBJECT_REF values through subject_redirects, so a payload written before a
// merge -- a live payload copied into a new edit, a draft, a pending revision -- names the merge
// target instead of the merged-away source.
//
// Cleans up only what the rewrite itself causes, so the payload validator never reports a problem the
// author didn't make: a rewritten value that now repeats another value of its field keeps just the
// better of them (PREFERRED over NORMAL, then the earlier), and one that now names the subject itself
// is dropped -- along with its field, if nothing else is left in it. A repeat the author typed in,
// with no redirect involved, is left for the validator to report.
final class SubjectReferenceRemapper {

    private static final Comparator<Entry> BEST_FIRST = Comparator
            .comparing((Entry entry) -> rankOf(entry.value()) == FactRank.PREFERRED ? 0 : 1)
            .thenComparingInt(Entry::index);

    private SubjectReferenceRemapper() {
    }

    static Set<UUID> referencedIds(@Nullable SubjectPayloadRequest payload) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (payload == null || payload.facts() == null) {
            return ids;
        }
        for (SubjectFactRequest fact : payload.facts()) {
            if (fact == null || fact.values() == null) {
                continue;
            }
            fact.values().stream()
                    .filter(Objects::nonNull)
                    .map(SubjectFactValueRequest::subjectId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    /**
     * Returns {@code payload} itself when no value names a key of {@code redirects}, so a caller can
     * tell by identity whether anything changed. {@code selfId} is null for a proposal.
     */
    static SubjectPayloadRequest remap(SubjectPayloadRequest payload, Map<UUID, UUID> redirects,
            @Nullable UUID selfId) {
        if (redirects.isEmpty() || payload.facts() == null) {
            return payload;
        }
        boolean isChanged = false;
        List<SubjectFactRequest> facts = new ArrayList<>();
        for (SubjectFactRequest fact : payload.facts()) {
            // A malformed entry is the validator's to report, not this rewrite's to drop.
            if (fact == null || fact.values() == null) {
                facts.add(fact);
                continue;
            }
            SubjectFactRequest remapped = remapFact(fact, redirects, selfId);
            if (remapped == fact) {
                facts.add(fact);
                continue;
            }
            isChanged = true;
            // Emptied only by dropping a rewritten self-reference.
            if (!remapped.values().isEmpty()) {
                facts.add(remapped);
            }
        }
        return isChanged
                ? new SubjectPayloadRequest(payload.names(), payload.aliases(), facts, payload.geometry(),
                        payload.mainImageMediaId())
                : payload;
    }

    private static SubjectFactRequest remapFact(SubjectFactRequest fact, Map<UUID, UUID> redirects,
            @Nullable UUID selfId) {
        List<Entry> entries = new ArrayList<>();
        boolean isAnyRemapped = false;
        for (int i = 0; i < fact.values().size(); i++) {
            SubjectFactValueRequest value = fact.values().get(i);
            UUID target = value == null || value.subjectId() == null ? null : redirects.get(value.subjectId());
            if (target == null) {
                entries.add(new Entry(i, value, false));
            } else {
                isAnyRemapped = true;
                entries.add(new Entry(i, withSubjectId(value, target), true));
            }
        }
        if (!isAnyRemapped) {
            return fact;
        }

        // A rewritten self-reference has no meaning left; one the author wrote is theirs to fix.
        entries.removeIf(entry -> entry.isRemapped() && entry.value().subjectId().equals(selfId));

        Map<UUID, List<Entry>> byId = new HashMap<>();
        entries.stream()
                .filter(entry -> entry.value() != null && entry.value().subjectId() != null)
                .forEach(entry -> byId.computeIfAbsent(entry.value().subjectId(), _ -> new ArrayList<>()).add(entry));
        Set<Integer> dropped = new LinkedHashSet<>();
        byId.values().stream()
                .filter(group -> group.size() > 1 && group.stream().anyMatch(Entry::isRemapped))
                .forEach(group -> group.stream().sorted(BEST_FIRST).skip(1).map(Entry::index).forEach(dropped::add));

        List<SubjectFactValueRequest> values = entries.stream()
                .filter(entry -> !dropped.contains(entry.index()))
                .map(Entry::value)
                .toList();
        return new SubjectFactRequest(fact.attribute(), values);
    }

    private static FactRank rankOf(SubjectFactValueRequest value) {
        return Objects.requireNonNullElse(value.rank(), FactRank.PREFERRED);
    }

    private static SubjectFactValueRequest withSubjectId(SubjectFactValueRequest value, UUID subjectId) {
        return new SubjectFactValueRequest(value.number(), value.option(), subjectId, value.bool(), value.date(),
                value.text(), value.localized(), value.asOf(), value.sourceId(), value.rank());
    }

    private record Entry(int index, SubjectFactValueRequest value, boolean isRemapped) {
    }
}

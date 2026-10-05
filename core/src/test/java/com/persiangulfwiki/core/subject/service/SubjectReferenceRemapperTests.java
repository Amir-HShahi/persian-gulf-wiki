package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectReferenceRemapperTests {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Map<UUID, UUID> REDIRECTS = Map.of(SOURCE, TARGET);

    private static SubjectFactValueRequest ref(UUID subjectId, @Nullable FactRank rank) {
        return new SubjectFactValueRequest(null, null, subjectId, null, null, null, null, null, null, rank);
    }

    private static SubjectPayloadRequest payload(SubjectFactRequest... facts) {
        return new SubjectPayloadRequest(new LocalizedTextRequest("نام", null, null), null, List.of(facts), null, null);
    }

    private static List<SubjectFactValueRequest> valuesOf(SubjectPayloadRequest payload, String attribute) {
        return payload.facts().stream().filter(fact -> attribute.equals(fact.attribute())).findFirst().orElseThrow()
                .values();
    }

    @Test
    void rewritesAReferenceToAMergedAwaySubject() {
        SubjectPayloadRequest remapped = SubjectReferenceRemapper.remap(
                payload(new SubjectFactRequest("located_in", List.of(ref(SOURCE, null), ref(OTHER, null)))),
                REDIRECTS, null);

        assertThat(valuesOf(remapped, "located_in")).extracting(SubjectFactValueRequest::subjectId)
                .containsExactly(TARGET, OTHER);
    }

    // The rewrite collides with a value already there: the better one stays, PREFERRED over NORMAL.
    @Test
    void keepsThePreferredOneWhenARewriteRepeatsAValue() {
        SubjectPayloadRequest remapped = SubjectReferenceRemapper.remap(payload(new SubjectFactRequest("province",
                List.of(ref(TARGET, FactRank.NORMAL), ref(SOURCE, FactRank.PREFERRED)))), REDIRECTS, null);

        assertThat(valuesOf(remapped, "province")).singleElement()
                .satisfies(value -> {
                    assertThat(value.subjectId()).isEqualTo(TARGET);
                    assertThat(value.rank()).isEqualTo(FactRank.PREFERRED);
                });
    }

    // A rewritten reference to the subject itself goes, and its field with it once empty.
    @Test
    void dropsARewrittenSelfReferenceAndTheFieldItEmpties() {
        SubjectPayloadRequest remapped = SubjectReferenceRemapper.remap(payload(
                        new SubjectFactRequest("located_in", List.of(ref(SOURCE, null))),
                        new SubjectFactRequest("province", List.of(ref(OTHER, null)))),
                REDIRECTS, TARGET);

        assertThat(remapped.facts()).extracting(SubjectFactRequest::attribute).containsExactly("province");
    }

    // Repeats and self-references the author typed are the validator's to report, not this rewrite's.
    @Test
    void leavesAPayloadWithoutRedirectedReferencesUntouched() {
        SubjectPayloadRequest original = payload(
                new SubjectFactRequest("located_in", List.of(ref(OTHER, null), ref(OTHER, null), ref(TARGET, null))),
                new SubjectFactRequest("empty", List.of()));

        assertThat(SubjectReferenceRemapper.remap(original, REDIRECTS, TARGET)).isSameAs(original);
    }
}

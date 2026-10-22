package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SubjectPayloadMergerTests {

    private static final UUID SOURCE_ID = UUID.randomUUID();

    private static SubjectFactValueRequest option(String code, @Nullable FactRank rank) {
        return new SubjectFactValueRequest(null, code, null, null, null, null, null, null, null, rank);
    }

    private static SubjectFactValueRequest number(String number, LocalDate asOf) {
        return new SubjectFactValueRequest(new BigDecimal(number), null, null, null, null, null, null, asOf, SOURCE_ID,
                null);
    }

    private static SubjectPayloadRequest payload(LocalizedTextRequest names, SubjectAliasesRequest aliases,
            SubjectFactRequest... facts) {
        return new SubjectPayloadRequest(names, aliases, List.of(facts), null, null);
    }

    private static SubjectAliasesRequest noAliases() {
        return new SubjectAliasesRequest(List.of(), List.of(), List.of());
    }

    private static List<SubjectFactValueRequest> valuesOf(SubjectPayloadRequest payload, String attribute) {
        return payload.facts().stream().filter(fact -> attribute.equals(fact.attribute())).findFirst().orElseThrow()
                .values();
    }

    @Test
    void keepsTheTargetsNamesFillsMissingOnesAndTurnsTheSourcesIntoAliases() {
        SubjectPayloadRequest merged = SubjectPayloadMerger.merge(
                payload(new LocalizedTextRequest("مقصد", null, null),
                        new SubjectAliasesRequest(List.of("کهن"), List.of(), List.of())),
                payload(new LocalizedTextRequest("مبدأ", "Source", null),
                        new SubjectAliasesRequest(List.of("کهن"), List.of("Old"), List.of())),
                Set.of(), null);

        assertThat(merged.names()).isEqualTo(new LocalizedTextRequest("مقصد", "Source", null));
        // "Source" became the English name, so it is not repeated as an alias.
        assertThat(merged.aliases()).isEqualTo(new SubjectAliasesRequest(List.of("کهن", "مبدأ"), List.of("Old"),
                List.of()));
    }

    // Single-valued: the target's PREFERRED value stays the shown one, the source's comes in as NORMAL.
    // Multi-valued: both sides' values, ranks kept. A value both sides hold appears once.
    @Test
    void demotesTheSourcesValuesOnlyWhereTheTargetsPreferredValueMustStayAlone() {
        SubjectPayloadRequest merged = SubjectPayloadMerger.merge(
                payload(new LocalizedTextRequest("مقصد", null, null), noAliases(),
                        new SubjectFactRequest("iucn_status", List.of(option("EN", null))),
                        new SubjectFactRequest("habitat", List.of(option("MANGROVE", null)))),
                payload(new LocalizedTextRequest("مبدأ", null, null), noAliases(),
                        new SubjectFactRequest("iucn_status", List.of(option("VU", FactRank.PREFERRED))),
                        new SubjectFactRequest("habitat",
                                List.of(option("MANGROVE", null), option("CORAL_REEF", null))),
                        new SubjectFactRequest("category", List.of(option("FISH", null)))),
                Set.of("habitat"), null);

        assertThat(valuesOf(merged, "iucn_status")).extracting(SubjectFactValueRequest::option,
                SubjectFactValueRequest::rank).containsExactly(
                        tuple("EN", null),
                        tuple("VU", FactRank.NORMAL));
        assertThat(valuesOf(merged, "habitat")).extracting(SubjectFactValueRequest::option)
                .containsExactly("MANGROVE", "CORAL_REEF");
        assertThat(valuesOf(merged, "category")).extracting(SubjectFactValueRequest::option).containsExactly("FISH");
    }

    // A target value that is only NORMAL leaves room for the source's PREFERRED one.
    @Test
    void keepsTheSourcesPreferredValueWhenTheTargetHasNone() {
        SubjectPayloadRequest merged = SubjectPayloadMerger.merge(
                payload(new LocalizedTextRequest("مقصد", null, null), noAliases(),
                        new SubjectFactRequest("iucn_status", List.of(option("EN", FactRank.NORMAL)))),
                payload(new LocalizedTextRequest("مبدأ", null, null), noAliases(),
                        new SubjectFactRequest("iucn_status", List.of(option("VU", FactRank.PREFERRED)))),
                Set.of(), null);

        assertThat(valuesOf(merged, "iucn_status")).extracting(SubjectFactValueRequest::rank)
                .containsExactly(FactRank.NORMAL, FactRank.PREFERRED);
    }

    // Numbers compare by value and date: 1000 and 1000.0 at one census are the same figure, another
    // census year is another statement.
    @Test
    void treatsTheSameNumberAtTheSameDateAsOneValue() {
        LocalDate census = LocalDate.of(2016, 1, 1);
        SubjectPayloadRequest merged = SubjectPayloadMerger.merge(
                payload(new LocalizedTextRequest("مقصد", null, null), noAliases(),
                        new SubjectFactRequest("population", List.of(number("1000", census)))),
                payload(new LocalizedTextRequest("مبدأ", null, null), noAliases(),
                        new SubjectFactRequest("population", List.of(number("1000.0", census),
                                number("900", LocalDate.of(2011, 1, 1))))),
                Set.of(), null);

        assertThat(valuesOf(merged, "population")).extracting(SubjectFactValueRequest::number)
                .containsExactly(new BigDecimal("1000"), new BigDecimal("900"));
    }
}

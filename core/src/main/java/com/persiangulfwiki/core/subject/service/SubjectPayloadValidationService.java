package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.source.service.SourceService;
import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectPayloadException;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectPayloadException.Violation;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Checks a subject revision's payload against the field vocabulary of its kind, collecting every
// failure in one pass (InvalidSubjectPayloadException -> 400 with a field path per failure).
//
// Runs on every write of a payload and again on submit and on approval, because the vocabulary
// can change in between: a field may become required, leave the kind, or have an option
// deprecated. The deprecation rules compare against the live payload -- a value already live may
// be kept unchanged, but a retired code may not be newly chosen. A reference to a deleted subject
// follows the same rule: kept if already live (it reappears on a restore), refused if new.
//
// References to a merged-away subject are first rewritten to the subject it was merged into, in
// both payloads, so a revision written before a merge still validates; the returned payload carries
// the rewritten ids.
@Service
@RequiredArgsConstructor
public class SubjectPayloadValidationService {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_ALIASES_PER_LANGUAGE = 20;
    static final int MAX_FACTS = 100;
    static final int MAX_VALUES_PER_FACT = 50;
    static final int MAX_TEXT_LENGTH = 500;
    // Bounds the parse cost of one request. A detailed coastline is a few hundred kilobytes of
    // WKT; anything larger belongs in a simplified form.
    static final int MAX_GEOMETRY_LENGTH = 500_000;

    // WGS84, stamped rather than taken from the input: V15 fixes SRID 4326 in every geometry
    // column's type and Postgres rejects a mismatch instead of reprojecting, while JTS gives a
    // freshly parsed WKT geometry SRID 0.
    private static final int SRID_WGS84 = 4326;

    private static final String ROOT = "payload";
    private static final String KEY_PREFIX = "validation.subjectPayload.";

    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final SubjectRepository subjectRepository;
    private final SourceService sourceService;
    private final SubjectMainImageService subjectMainImageService;
    private final SubjectService subjectService;

    /**
     * subjectId is null for a proposal; submittedLive, the live revision's payload, is null when the
     * subject has no approved revision. Throws InvalidSubjectPayloadException listing every failure;
     * never returns partially.
     */
    @Transactional(readOnly = true)
    public ValidatedSubjectPayload validate(SubjectKind kind, @Nullable UUID subjectId, SubjectPayloadRequest submitted,
            @Nullable SubjectPayloadRequest submittedLive) {
        SubjectPayloadRequest payload = subjectService.followRedirects(submitted, subjectId);
        SubjectPayloadRequest livePayload = submittedLive == null ? null
                : subjectService.followRedirects(submittedLive, subjectId);
        List<Violation> violations = new ArrayList<>();

        checkNames(payload.names(), violations);
        SubjectAliasesRequest aliases = normalizeAliases(payload.aliases());
        checkAliases(aliases, violations);
        List<SubjectFactRequest> facts = Objects.requireNonNullElse(payload.facts(), List.of());
        List<ResolvedFactValue> resolved = checkFacts(kind, subjectId, facts,
                livePayload == null ? List.of() : Objects.requireNonNullElse(livePayload.facts(), List.of()),
                violations);
        String geometryWkt = payload.geometry() == null || payload.geometry().isBlank() ? null : payload.geometry();
        Geometry geometry = checkGeometry(kind, geometryWkt, violations);
        checkMainImage(subjectId, payload.mainImageMediaId(), violations);

        if (!violations.isEmpty()) {
            throw new InvalidSubjectPayloadException(violations);
        }

        SubjectPayloadRequest normalized = new SubjectPayloadRequest(payload.names(), aliases,
                facts.stream().map(SubjectPayloadValidationService::withDefaultRanks).toList(), geometryWkt,
                payload.mainImageMediaId());
        return new ValidatedSubjectPayload(normalized, resolved, geometry);
    }

    private void checkNames(@Nullable LocalizedTextRequest names, List<Violation> violations) {
        String path = ROOT + ".names";
        if (names == null || isBlank(names.fa())) {
            violations.add(violation(path + ".fa", "nameFaRequired"));
        } else if (names.fa().length() > MAX_NAME_LENGTH) {
            violations.add(violation(path + ".fa", "nameTooLong", MAX_NAME_LENGTH));
        }
        if (names != null) {
            checkOptionalText(names.en(), path + ".en", MAX_NAME_LENGTH, "nameBlank", "nameTooLong", violations);
            checkOptionalText(names.ar(), path + ".ar", MAX_NAME_LENGTH, "nameBlank", "nameTooLong", violations);
        }
    }

    private void checkAliases(SubjectAliasesRequest aliases, List<Violation> violations) {
        checkAliasList(aliases.fa(), ROOT + ".aliases.fa", violations);
        checkAliasList(aliases.en(), ROOT + ".aliases.en", violations);
        checkAliasList(aliases.ar(), ROOT + ".aliases.ar", violations);
    }

    private void checkAliasList(List<String> aliases, String path, List<Violation> violations) {
        if (aliases.size() > MAX_ALIASES_PER_LANGUAGE) {
            violations.add(violation(path, "tooManyAliases", MAX_ALIASES_PER_LANGUAGE));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < aliases.size(); i++) {
            String alias = aliases.get(i);
            String itemPath = path + "[" + i + "]";
            if (isBlank(alias)) {
                violations.add(violation(itemPath, "aliasBlank"));
            } else if (alias.length() > MAX_NAME_LENGTH) {
                violations.add(violation(itemPath, "aliasTooLong", MAX_NAME_LENGTH));
            } else if (!seen.add(alias)) {
                // uq_subject_aliases_subject_language_value would otherwise fail the approval.
                violations.add(violation(itemPath, "aliasDuplicate"));
            }
        }
    }

    private List<ResolvedFactValue> checkFacts(SubjectKind kind, @Nullable UUID subjectId,
            List<SubjectFactRequest> facts, List<SubjectFactRequest> liveFacts, List<Violation> violations) {
        if (facts.size() > MAX_FACTS) {
            violations.add(violation(ROOT + ".facts", "tooManyFacts", MAX_FACTS));
            return List.of();
        }

        Map<String, AttributeDefinition> definitions = attributeDefinitionRepository.findByKind(kind).stream()
                .collect(Collectors.toMap(AttributeDefinition::getKey, Function.identity()));
        Map<UUID, Map<String, AttributeOption>> optionsByDefinition = definitions.isEmpty() ? Map.of()
                : attributeOptionRepository.findByAttributeIdIn(definitions.values().stream()
                                .map(AttributeDefinition::getId).toList()).stream()
                        .collect(Collectors.groupingBy(AttributeOption::getAttributeId,
                                Collectors.toMap(AttributeOption::getCode, Function.identity())));
        Map<String, List<SubjectFactValueRequest>> liveValues = liveFacts.stream()
                .filter(fact -> fact.attribute() != null && fact.values() != null)
                .collect(Collectors.toMap(SubjectFactRequest::attribute, SubjectFactRequest::values,
                        (first, _) -> first));

        // Looked up once for the whole payload rather than per value.
        List<SubjectFactValueRequest> allValues = facts.stream()
                .flatMap(fact -> fact.values() == null ? Stream.empty() : fact.values().stream())
                .filter(Objects::nonNull)
                .toList();
        List<Subject> referenced = subjectRepository.findAllById(allValues.stream()
                .map(SubjectFactValueRequest::subjectId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, SubjectKind> referencedKinds = referenced.stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getKind));
        Set<UUID> deletedReferences = referenced.stream().filter(Subject::isDeleted).map(Subject::getId)
                .collect(Collectors.toSet());
        Set<UUID> existingSources = sourceService.findExistingIds(allValues.stream()
                .map(SubjectFactValueRequest::sourceId).filter(Objects::nonNull).collect(Collectors.toSet()));

        FactContext context = new FactContext(subjectId, optionsByDefinition, liveValues, referencedKinds,
                deletedReferences, existingSources);
        List<ResolvedFactValue> resolved = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < facts.size(); i++) {
            String path = ROOT + ".facts[" + i + "]";
            SubjectFactRequest fact = facts.get(i);
            if (fact == null || isBlank(fact.attribute())) {
                violations.add(violation(path + ".attribute", "attributeMissing"));
                continue;
            }
            AttributeDefinition definition = definitions.get(fact.attribute());
            if (definition == null) {
                violations.add(violation(path + ".attribute", attributeDefinitionRepository.existsByKey(fact.attribute())
                        ? "attributeNotForKind" : "attributeUnknown", fact.attribute()));
                continue;
            }
            if (!seenKeys.add(definition.getKey())) {
                violations.add(violation(path + ".attribute", "attributeDuplicate"));
                continue;
            }
            // Decision 14: a derived value is computed at read time, never typed in.
            if (definition.isDerived()) {
                violations.add(violation(path + ".attribute", "attributeDerived", fact.attribute()));
                continue;
            }
            resolved.addAll(checkFact(definition, fact, path, context, violations));
        }

        definitions.values().stream()
                .filter(definition -> definition.isRequired() && !definition.isDeprecated() && !definition.isDerived())
                .filter(definition -> !seenKeys.contains(definition.getKey()))
                .map(AttributeDefinition::getKey)
                .sorted()
                .forEach(key -> violations.add(violation(ROOT + ".facts", "attributeRequired", key)));
        return resolved;
    }

    private List<ResolvedFactValue> checkFact(AttributeDefinition definition, SubjectFactRequest fact, String path,
            FactContext context, List<Violation> violations) {
        List<SubjectFactValueRequest> values = Objects.requireNonNullElse(fact.values(), List.of());
        if (values.isEmpty()) {
            violations.add(violation(path + ".values", "valuesRequired"));
            return List.of();
        }
        if (values.size() > MAX_VALUES_PER_FACT) {
            violations.add(violation(path + ".values", "tooManyValues", MAX_VALUES_PER_FACT));
            return List.of();
        }
        List<SubjectFactValueRequest> liveValues = context.liveValues().getOrDefault(definition.getKey(), List.of());
        // A retired field keeps what it already says, but takes no new or changed values.
        if (definition.isDeprecated()
                && !values.stream().map(SubjectPayloadValidationService::withDefaultRank).toList()
                        .equals(liveValues.stream().map(SubjectPayloadValidationService::withDefaultRank).toList())) {
            violations.add(violation(path + ".attribute", "attributeDeprecated"));
            return List.of();
        }

        List<ResolvedFactValue> resolved = new ArrayList<>();
        Set<Object> seenValues = new HashSet<>();
        int preferredCount = 0;
        for (int j = 0; j < values.size(); j++) {
            String valuePath = path + ".values[" + j + "]";
            SubjectFactValueRequest value = values.get(j);
            if (value == null) {
                violations.add(violation(valuePath, "valueMismatch", expectedField(definition)));
                continue;
            }
            FactRank rank = Objects.requireNonNullElse(value.rank(), FactRank.PREFERRED);
            if (rank == FactRank.PREFERRED) {
                preferredCount++;
            }
            int before = violations.size();
            UUID optionId = checkValue(definition, value, valuePath, liveValues, context, violations);
            checkSource(definition, value, valuePath, context, violations);
            Object identity = optionId != null ? optionId : value.subjectId();
            if (identity != null && !seenValues.add(identity)) {
                violations.add(violation(valuePath, "valueDuplicate"));
            }
            if (violations.size() == before) {
                resolved.add(new ResolvedFactValue(definition.getId(), j, value.number(), optionId,
                        value.subjectId(), value.bool(), value.date(), value.text(), value.localized(),
                        value.asOf(), rank, value.sourceId()));
            }
        }
        if (!definition.isMulti() && preferredCount > 1) {
            violations.add(violation(path + ".values", "tooManyPreferred"));
        }
        return resolved;
    }

    // Returns the resolved option id for an ENUM value, null otherwise.
    private @Nullable UUID checkValue(AttributeDefinition definition, SubjectFactValueRequest value, String path,
            List<SubjectFactValueRequest> liveValues, FactContext context, List<Violation> violations) {
        long setCount = Stream.of(value.number(), value.option(), value.subjectId(), value.bool(), value.date(),
                value.text(), value.localized()).filter(Objects::nonNull).count();
        Object expected = switch (definition.getValueType()) {
            case NUMBER -> value.number();
            case ENUM -> value.option();
            case SUBJECT_REF -> value.subjectId();
            case BOOL -> value.bool();
            case DATE -> value.date();
            case PLAIN_TEXT -> value.text();
            case LOCALIZED_TEXT -> value.localized();
        };
        if (setCount != 1 || expected == null) {
            violations.add(violation(path, "valueMismatch", expectedField(definition)));
            return null;
        }

        switch (definition.getValueType()) {
            case NUMBER -> checkNumber(definition, value.number(), path + ".number", violations);
            case ENUM -> {
                return checkOption(definition, value.option(), path + ".option", liveValues, context, violations);
            }
            case SUBJECT_REF -> checkReference(definition, value.subjectId(), path + ".subjectId", liveValues, context,
                    violations);
            case PLAIN_TEXT -> checkText(value.text(), path + ".text", violations);
            case LOCALIZED_TEXT -> checkLocalized(value.localized(), path + ".localized", violations);
            case BOOL, DATE -> {
                // The type alone is the whole rule.
            }
        }
        return null;
    }

    private void checkNumber(AttributeDefinition definition, BigDecimal number, String path,
            List<Violation> violations) {
        if (definition.getMinValue() != null && number.compareTo(definition.getMinValue()) < 0) {
            violations.add(violation(path, "numberBelowMin", definition.getMinValue().toPlainString()));
        }
        if (definition.getMaxValue() != null && number.compareTo(definition.getMaxValue()) > 0) {
            violations.add(violation(path, "numberAboveMax", definition.getMaxValue().toPlainString()));
        }
    }

    private @Nullable UUID checkOption(AttributeDefinition definition, String code, String path,
            List<SubjectFactValueRequest> liveValues, FactContext context, List<Violation> violations) {
        AttributeOption option = context.optionsByDefinition().getOrDefault(definition.getId(), Map.of()).get(code);
        if (option == null) {
            violations.add(violation(path, "optionUnknown", code));
            return null;
        }
        boolean isLive = liveValues.stream().anyMatch(live -> code.equals(live.option()));
        if (option.isDeprecated() && !isLive) {
            violations.add(violation(path, "optionDeprecated", code));
            return null;
        }
        return option.getId();
    }

    private void checkReference(AttributeDefinition definition, UUID referencedId, String path,
            List<SubjectFactValueRequest> liveValues, FactContext context, List<Violation> violations) {
        SubjectKind referencedKind = context.referencedKinds().get(referencedId);
        boolean isLive = liveValues.stream().anyMatch(live -> referencedId.equals(live.subjectId()));
        // A deleted subject is hidden, so newly pointing at one reads as pointing at nothing.
        if (referencedKind == null || (context.deletedReferences().contains(referencedId) && !isLive)) {
            violations.add(violation(path, "referenceNotFound"));
        } else if (referencedId.equals(context.subjectId())) {
            violations.add(violation(path, "referenceSelf"));
        } else if (referencedKind != definition.getRefKind()) {
            violations.add(violation(path, "referenceWrongKind", String.valueOf(definition.getRefKind())));
        }
    }

    private void checkText(String text, String path, List<Violation> violations) {
        if (isBlank(text)) {
            violations.add(violation(path, "textBlank"));
        } else if (text.length() > MAX_TEXT_LENGTH) {
            violations.add(violation(path, "textTooLong", MAX_TEXT_LENGTH));
        }
    }

    private void checkLocalized(LocalizedTextRequest localized, String path, List<Violation> violations) {
        if (isBlank(localized.fa())) {
            violations.add(violation(path + ".fa", "localizedFaRequired"));
        } else if (localized.fa().length() > MAX_TEXT_LENGTH) {
            violations.add(violation(path + ".fa", "textTooLong", MAX_TEXT_LENGTH));
        }
        checkOptionalText(localized.en(), path + ".en", MAX_TEXT_LENGTH, "textBlank", "textTooLong", violations);
        checkOptionalText(localized.ar(), path + ".ar", MAX_TEXT_LENGTH, "textBlank", "textTooLong", violations);
    }

    private void checkSource(AttributeDefinition definition, SubjectFactValueRequest value, String path,
            FactContext context, List<Violation> violations) {
        if (value.sourceId() == null) {
            // A number is the kind of value most often disputed and most often out of date, so it
            // never goes in without saying where it came from.
            if (definition.getValueType() == AttributeValueType.NUMBER) {
                violations.add(violation(path + ".sourceId", "sourceRequired"));
            }
        } else if (!context.existingSources().contains(value.sourceId())) {
            violations.add(violation(path + ".sourceId", "sourceNotFound"));
        }
    }

    private @Nullable Geometry checkGeometry(SubjectKind kind, @Nullable String wkt, List<Violation> violations) {
        String path = ROOT + ".geometry";
        if (wkt == null) {
            return null;
        }
        if (wkt.length() > MAX_GEOMETRY_LENGTH) {
            violations.add(violation(path, "geometryTooLarge", MAX_GEOMETRY_LENGTH));
            return null;
        }
        Geometry geometry;
        try {
            // A new reader per call: WKTReader holds parse state and is not thread-safe.
            geometry = new WKTReader(new GeometryFactory(new PrecisionModel(), SRID_WGS84)).read(wkt);
        } catch (ParseException | IllegalArgumentException e) {
            // IllegalArgumentException too: JTS throws it for parseable but invalid WKT, such as a
            // polygon ring that does not close.
            violations.add(violation(path, "geometryInvalid"));
            return null;
        }
        Geometry typed = switch (kind) {
            case ISLAND, PORT -> geometry instanceof Point ? geometry : null;
            case OIL_FIELD, SPECIES, STRAIT, FISHING_GROUND -> geometry instanceof Polygon ? geometry : null;
            // A single ring is common, and wrapping it is the column's concern, not the author's.
            case PROVINCE -> switch (geometry) {
                case MultiPolygon multiPolygon -> multiPolygon;
                case Polygon polygon -> polygon.getFactory().createMultiPolygon(new Polygon[] {polygon});
                default -> null;
            };
        };
        if (typed == null) {
            violations.add(violation(path, "geometryWrongType", expectedGeometry(kind)));
        }
        return typed;
    }

    private void checkMainImage(@Nullable UUID subjectId, @Nullable UUID mediaId, List<Violation> violations) {
        if (mediaId == null) {
            return;
        }
        // A proposal has no articles yet, so nothing can qualify.
        if (subjectId == null || !subjectMainImageService.isPublishedImageOfSubject(mediaId, subjectId)) {
            violations.add(violation(ROOT + ".mainImageMediaId", "mainImageInvalid"));
        }
    }

    private void checkOptionalText(@Nullable String text, String path, int maxLength, String blankKey,
            String tooLongKey, List<Violation> violations) {
        if (text == null) {
            return;
        }
        // "" would be stored and shown instead of falling back to Farsi.
        if (text.isBlank()) {
            violations.add(violation(path, blankKey));
        } else if (text.length() > maxLength) {
            violations.add(violation(path, tooLongKey, maxLength));
        }
    }

    private static SubjectAliasesRequest normalizeAliases(@Nullable SubjectAliasesRequest aliases) {
        if (aliases == null) {
            return new SubjectAliasesRequest(List.of(), List.of(), List.of());
        }
        return new SubjectAliasesRequest(Objects.requireNonNullElse(aliases.fa(), List.of()),
                Objects.requireNonNullElse(aliases.en(), List.of()),
                Objects.requireNonNullElse(aliases.ar(), List.of()));
    }

    private static SubjectFactRequest withDefaultRanks(SubjectFactRequest fact) {
        return new SubjectFactRequest(fact.attribute(),
                fact.values().stream().map(SubjectPayloadValidationService::withDefaultRank).toList());
    }

    private static SubjectFactValueRequest withDefaultRank(SubjectFactValueRequest value) {
        return value.rank() != null ? value : new SubjectFactValueRequest(value.number(), value.option(),
                value.subjectId(), value.bool(), value.date(), value.text(), value.localized(), value.asOf(),
                value.sourceId(), FactRank.PREFERRED);
    }

    private static String expectedField(AttributeDefinition definition) {
        return switch (definition.getValueType()) {
            case NUMBER -> "number";
            case ENUM -> "option";
            case SUBJECT_REF -> "subjectId";
            case BOOL -> "bool";
            case DATE -> "date";
            case PLAIN_TEXT -> "text";
            case LOCALIZED_TEXT -> "localized";
        };
    }

    private static String expectedGeometry(SubjectKind kind) {
        return switch (kind) {
            case ISLAND, PORT -> "POINT";
            case OIL_FIELD, SPECIES, STRAIT, FISHING_GROUND -> "POLYGON";
            case PROVINCE -> "POLYGON, MULTIPOLYGON";
        };
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static Violation violation(String field, String rule, Object... args) {
        return new Violation(field, KEY_PREFIX + rule, Stream.of(args).map(String::valueOf).toList());
    }

    // What checking one fact value needs beyond the definition, looked up once per payload.
    private record FactContext(
            @Nullable UUID subjectId,
            Map<UUID, Map<String, AttributeOption>> optionsByDefinition,
            Map<String, List<SubjectFactValueRequest>> liveValues,
            Map<UUID, SubjectKind> referencedKinds,
            Set<UUID> deletedReferences,
            Set<UUID> existingSources) {
    }
}

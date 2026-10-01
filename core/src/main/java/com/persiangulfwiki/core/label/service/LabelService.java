package com.persiangulfwiki.core.label.service;

import com.persiangulfwiki.core.attribute.exception.AttributeDefinitionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeGroupNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionNotFoundException;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.label.dto.LabelReferentKind;
import com.persiangulfwiki.core.label.dto.LabelResponse;
import com.persiangulfwiki.core.label.dto.LabelValuesRequest;
import com.persiangulfwiki.core.label.dto.LabelValuesResponse;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.exception.InvalidLabelReferentException;
import com.persiangulfwiki.core.label.exception.UnsupportedLabelLanguageException;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LabelService {

    private static final String CANONICAL_LANGUAGE = SupportedLocales.FARSI.getLanguage();
    private static final String ENGLISH = Locale.ENGLISH.getLanguage();
    private static final String ARABIC = SupportedLocales.ARABIC.getLanguage();

    private final LabelRepository labelRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final AuditLogService auditLogService;

    /**
     * Admin write of one label. Only the vocabulary — GROUP, ATTRIBUTE, OPTION and KIND — is writable here;
     * SUBJECT is rejected, because a subject's names change only through a moderated revision.
     */
    @Transactional
    public LabelResponse upsert(UUID actingAdminId, String referentKindRaw, String referentIdRaw, String languageRaw,
            String value) {
        LabelReferent referent = parseVocabularyReferent(referentKindRaw, referentIdRaw);
        String language = parseLanguage(languageRaw);

        Label label = findAll(referent).stream()
                .filter(candidate -> candidate.getLanguage().equals(language))
                .findFirst()
                .orElse(null);
        String before = label == null ? null : label.getValue();
        if (label == null) {
            label = newLabel(referent, language, value);
        } else {
            label.setValue(value);
        }
        // Flushed so @PreUpdate has stamped updated_at before it is read back below.
        label = labelRepository.saveAndFlush(label);

        auditLogService.record(actingAdminId, "LABEL_UPSERT", null,
                "referent=" + referent.describe() + ", language=" + language + ", before=" + before + ", after=" + value);
        return new LabelResponse(referent.type(), referentIdRaw(referent), language, label.getValue(),
                label.getUpdatedAt());
    }

    /** Inserts every non-null value; the caller guarantees the referent has no labels yet. */
    @Transactional
    public void createAll(LabelReferent referent, LabelValuesRequest values) {
        Map<String, String> byLanguage = new HashMap<>();
        byLanguage.put(CANONICAL_LANGUAGE, values.fa());
        if (values.en() != null) {
            byLanguage.put(ENGLISH, values.en());
        }
        if (values.ar() != null) {
            byLanguage.put(ARABIC, values.ar());
        }
        byLanguage.forEach((language, value) -> labelRepository.save(newLabel(referent, language, value)));
    }

    @Transactional(readOnly = true)
    public LabelValuesResponse values(LabelReferent referent) {
        Map<String, String> byLanguage = findAll(referent).stream()
                .collect(Collectors.toMap(Label::getLanguage, Label::getValue));
        return new LabelValuesResponse(byLanguage.get(CANONICAL_LANGUAGE), byLanguage.get(ENGLISH),
                byLanguage.get(ARABIC));
    }

    /** Ids with no label in the language or in Farsi are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, LocalizedLabelResponse> resolveGroups(Collection<UUID> groupIds, String language) {
        return groupIds.isEmpty() ? Map.of()
                : resolve(labelRepository.findByGroupIdIn(groupIds), Label::getGroupId, language);
    }

    /** Ids with no label in the language or in Farsi are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, LocalizedLabelResponse> resolveAttributes(Collection<UUID> attributeIds, String language) {
        return attributeIds.isEmpty() ? Map.of()
                : resolve(labelRepository.findByAttributeIdIn(attributeIds), Label::getAttributeId, language);
    }

    /** Ids with no label in the language or in Farsi are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, LocalizedLabelResponse> resolveOptions(Collection<UUID> optionIds, String language) {
        return optionIds.isEmpty() ? Map.of()
                : resolve(labelRepository.findByOptionIdIn(optionIds), Label::getOptionId, language);
    }

    /** Empty when the kind has no label in the language or in Farsi. */
    @Transactional(readOnly = true)
    public Optional<LocalizedLabelResponse> resolveKind(SubjectKind kind, String language) {
        return Optional.ofNullable(
                resolve(labelRepository.findByKindIn(List.of(kind)), Label::getKind, language).get(kind));
    }

    // Requested language first, then Farsi marked as a fallback. Never English or Arabic as a
    // fallback: Farsi is the only language every referent is guaranteed to have.
    private <K> Map<K, LocalizedLabelResponse> resolve(List<Label> labels, Function<Label, K> referentOf,
            String language) {
        Map<K, Map<String, String>> byReferent = labels.stream().collect(Collectors.groupingBy(
                referentOf, Collectors.toMap(Label::getLanguage, Label::getValue)));
        Map<K, LocalizedLabelResponse> resolved = new HashMap<>();
        byReferent.forEach((referent, byLanguage) -> {
            if (byLanguage.containsKey(language)) {
                resolved.put(referent, new LocalizedLabelResponse(byLanguage.get(language), language, false));
            } else if (byLanguage.containsKey(CANONICAL_LANGUAGE)) {
                resolved.put(referent,
                        new LocalizedLabelResponse(byLanguage.get(CANONICAL_LANGUAGE), CANONICAL_LANGUAGE, true));
            }
        });
        return resolved;
    }

    private List<Label> findAll(LabelReferent referent) {
        return switch (referent.type()) {
            case GROUP -> labelRepository.findByGroupIdIn(List.of(referent.id()));
            case ATTRIBUTE -> labelRepository.findByAttributeIdIn(List.of(referent.id()));
            case OPTION -> labelRepository.findByOptionIdIn(List.of(referent.id()));
            case SUBJECT -> labelRepository.findBySubjectIdIn(List.of(referent.id()));
            case KIND -> labelRepository.findByKindIn(List.of(referent.subjectKind()));
        };
    }

    private Label newLabel(LabelReferent referent, String language, String value) {
        Label.LabelBuilder<?, ?> builder = Label.builder().language(language).value(value);
        return switch (referent.type()) {
            case GROUP -> builder.groupId(referent.id()).build();
            case ATTRIBUTE -> builder.attributeId(referent.id()).build();
            case OPTION -> builder.optionId(referent.id()).build();
            case SUBJECT -> builder.subjectId(referent.id()).build();
            case KIND -> builder.kind(referent.subjectKind()).build();
        };
    }

    // Parses the raw path segments rather than binding enums/UUIDs on the controller, so every
    // malformed form — unknown kind, SUBJECT, a non-UUID id, an unknown kind name — is one
    // translated 400 carrying INVALID_LABEL_REFERENT. A well-formed id that names nothing is a
    // 404 from the referent's own not-found exception.
    private LabelReferent parseVocabularyReferent(String referentKindRaw, String referentIdRaw) {
        LabelReferentKind type = parseEnum(LabelReferentKind.class, referentKindRaw);
        if (type == null || type == LabelReferentKind.SUBJECT) {
            throw new InvalidLabelReferentException("label referent kind not writable here: " + referentKindRaw);
        }
        if (type == LabelReferentKind.KIND) {
            SubjectKind kind = parseEnum(SubjectKind.class, referentIdRaw);
            if (kind == null) {
                throw new InvalidLabelReferentException("unknown subject kind: " + referentIdRaw);
            }
            return LabelReferent.kind(kind);
        }

        UUID id = parseUuid(referentIdRaw);
        return switch (type) {
            case GROUP -> {
                if (!attributeGroupRepository.existsById(id)) {
                    throw new AttributeGroupNotFoundException("attribute group not found: " + id);
                }
                yield LabelReferent.group(id);
            }
            case ATTRIBUTE -> {
                if (!attributeDefinitionRepository.existsById(id)) {
                    throw new AttributeDefinitionNotFoundException("attribute definition not found: " + id);
                }
                yield LabelReferent.attribute(id);
            }
            case OPTION -> {
                if (!attributeOptionRepository.existsById(id)) {
                    throw new AttributeOptionNotFoundException("attribute option not found: " + id);
                }
                yield LabelReferent.option(id);
            }
            case SUBJECT, KIND -> throw new IllegalStateException("handled above: " + type);
        };
    }

    private String parseLanguage(String languageRaw) {
        String language = languageRaw.toLowerCase(Locale.ROOT);
        boolean isSupported = SupportedLocales.ALL.stream()
                .anyMatch(locale -> locale.getLanguage().equals(language));
        if (!isSupported) {
            throw new UnsupportedLabelLanguageException("unsupported label language: " + languageRaw);
        }
        return language;
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidLabelReferentException("label referent id is not a UUID: " + raw);
        }
    }

    private static <E extends Enum<E>> @Nullable E parseEnum(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String referentIdRaw(LabelReferent referent) {
        return referent.type() == LabelReferentKind.KIND ? referent.subjectKind().name() : referent.id().toString();
    }
}

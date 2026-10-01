package com.persiangulfwiki.core.label.service;

import com.persiangulfwiki.core.label.dto.LabelReferentKind;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// The thing a label belongs to: an id for the four row-backed referents, a SubjectKind for KIND.
// Exactly one of `id`/`subjectKind` is set, matching `type` — use the factories.
public record LabelReferent(LabelReferentKind type, @Nullable UUID id, @Nullable SubjectKind subjectKind) {

    public static LabelReferent group(UUID id) {
        return new LabelReferent(LabelReferentKind.GROUP, id, null);
    }

    public static LabelReferent attribute(UUID id) {
        return new LabelReferent(LabelReferentKind.ATTRIBUTE, id, null);
    }

    public static LabelReferent option(UUID id) {
        return new LabelReferent(LabelReferentKind.OPTION, id, null);
    }

    public static LabelReferent subject(UUID id) {
        return new LabelReferent(LabelReferentKind.SUBJECT, id, null);
    }

    public static LabelReferent kind(SubjectKind kind) {
        return new LabelReferent(LabelReferentKind.KIND, null, kind);
    }

    public String describe() {
        return type + ":" + (type == LabelReferentKind.KIND ? subjectKind : id);
    }
}

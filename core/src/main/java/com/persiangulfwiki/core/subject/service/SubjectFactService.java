package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;

// Read-only questions about the live facts, for the field vocabulary's admin writes: a change to
// a field must not leave published facts that no longer fit it.
@Service
@RequiredArgsConstructor
public class SubjectFactService {

    private final SubjectFactRepository subjectFactRepository;

    @Transactional(readOnly = true)
    public boolean hasFacts(UUID attributeId) {
        return subjectFactRepository.existsByAttributeId(attributeId);
    }

    @Transactional(readOnly = true)
    public boolean hasFactsOnKinds(UUID attributeId, Collection<SubjectKind> kinds) {
        return !kinds.isEmpty() && subjectFactRepository.existsByAttributeIdAndSubjectKindIn(attributeId, kinds);
    }

    @Transactional(readOnly = true)
    public boolean hasSubjectWithSeveralPreferredValues(UUID attributeId) {
        return subjectFactRepository.existsSubjectWithSeveralPreferredValues(attributeId);
    }
}

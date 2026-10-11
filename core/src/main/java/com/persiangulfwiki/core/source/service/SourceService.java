package com.persiangulfwiki.core.source.service;

import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.source.dto.CreateSourceRequest;
import com.persiangulfwiki.core.source.dto.SourceResponse;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.exception.SourceNotFoundException;
import com.persiangulfwiki.core.source.repository.SourceRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SourceService {

    private final SourceRepository sourceRepository;

    @Transactional
    public SourceResponse create(UUID creatorUserId, CreateSourceRequest request) {
        return toResponse(sourceRepository.save(Source.builder()
                .title(request.title())
                .url(request.url())
                .publisher(request.publisher())
                .publishedOn(request.publishedOn())
                .createdByUserId(creatorUserId)
                .build()));
    }

    @Transactional(readOnly = true)
    public SourceResponse get(UUID sourceId) {
        return toResponse(sourceRepository.findById(sourceId).orElseThrow(SourceNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public PageResponse<SourceResponse> list(Pageable pageable) {
        Page<Source> sources = sourceRepository.findAll(pageable);
        return new PageResponse<>(sources.stream().map(this::toResponse).toList(), sources.getTotalElements());
    }

    /** Unknown ids are left out rather than reported. */
    @Transactional(readOnly = true)
    public List<SourceResponse> findAll(Collection<UUID> sourceIds) {
        return sourceIds.isEmpty() ? List.of()
                : sourceRepository.findAllById(sourceIds).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Set<UUID> findExistingIds(Collection<UUID> sourceIds) {
        return sourceIds.isEmpty() ? Set.of()
                : sourceRepository.findAllById(sourceIds).stream().map(Source::getId).collect(Collectors.toSet());
    }

    private SourceResponse toResponse(Source source) {
        return new SourceResponse(
                source.getId(),
                source.getTitle(),
                source.getUrl(),
                source.getPublisher(),
                source.getPublishedOn(),
                source.getCreatedByUserId(),
                source.getCreatedAt());
    }
}

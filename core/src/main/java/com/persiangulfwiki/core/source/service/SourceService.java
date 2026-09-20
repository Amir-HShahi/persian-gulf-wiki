package com.persiangulfwiki.core.source.service;

import com.persiangulfwiki.core.source.dto.CreateSourceRequest;
import com.persiangulfwiki.core.source.dto.SourceResponse;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.exception.SourceNotFoundException;
import com.persiangulfwiki.core.source.repository.SourceRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
    public List<SourceResponse> list(Pageable pageable) {
        return sourceRepository.findAll(pageable).getContent().stream().map(this::toResponse).toList();
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

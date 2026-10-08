package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.attribute.exception.AttributeDefinitionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeGroupNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionNotFoundException;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.dev.dto.DevTestLabelRequest;
import com.persiangulfwiki.core.dev.dto.DevTestLabelResponse;
import com.persiangulfwiki.core.label.dto.LabelReferentKind;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.exception.InvalidLabelReferentException;
import com.persiangulfwiki.core.label.exception.LabelNotFoundException;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.topic.exception.TopicNotFoundException;
import com.persiangulfwiki.core.topic.repository.TopicRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Writes a label on a referent the dev surface minted, so a suite can reach every label state —
// a missing English label (fallback), a missing Farsi one, a named subject — without the admin
// endpoint. Same two gates and the same reasoning as DevTestSubjectController — see that class.
//
// labels has no dev_marker: a label is disposable exactly when its referent is, and it goes with
// the referent through ON DELETE CASCADE (V22). So both routes refuse a referent that wasn't
// minted, which also keeps them from overwriting a seeded name.
@RestController
@RequestMapping("/api/dev/test-labels")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Labels", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestLabelController {

    private final LabelRepository labelRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final SubjectRepository subjectRepository;
    private final TopicRepository topicRepository;

    @Operation(summary = "Set a label on a minted referent", description = "Creates or replaces the label of a test group, "
            + "definition, option, subject or topic in one language. KIND is refused, and so is any referent not minted by a "
            + "dev fixture endpoint.")
    @ApiResponse(responseCode = "201", description = "The stored label.")
    @ApiResponse(responseCode = "400", description = "`referentKind` is KIND, or a field is missing or invalid.")
    @ApiResponse(responseCode = "404", description = "No such referent, or it was not minted by a dev fixture endpoint.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestLabelResponse mint(@Valid @RequestBody DevTestLabelRequest request) {
        requireMintedReferent(request.referentKind(), request.referentId());

        Label label = existing(request.referentKind(), request.referentId()).stream()
                .filter(candidate -> candidate.getLanguage().equals(request.language()))
                .findFirst()
                .orElse(null);
        if (label == null) {
            Label.LabelBuilder<?, ?> builder = Label.builder().language(request.language()).value(request.value());
            label = switch (request.referentKind()) {
                case GROUP -> builder.groupId(request.referentId()).build();
                case ATTRIBUTE -> builder.attributeId(request.referentId()).build();
                case OPTION -> builder.optionId(request.referentId()).build();
                case SUBJECT -> builder.subjectId(request.referentId()).build();
                case TOPIC -> builder.topicId(request.referentId()).build();
                case KIND -> throw new IllegalStateException("refused by requireMintedReferent");
            };
        } else {
            label.setValue(request.value());
        }
        label = labelRepository.save(label);
        return new DevTestLabelResponse(label.getId(), request.referentKind(), request.referentId(), label.getLanguage(),
                label.getValue());
    }

    @Operation(summary = "Delete a label on a minted referent", description = "Deletes a label whose referent was minted by "
            + "a dev fixture endpoint. Refuses anything else, including every seeded label.")
    @ApiResponse(responseCode = "204", description = "The label was deleted.")
    @ApiResponse(responseCode = "404", description = "No such label, or its referent was not minted by a dev fixture "
            + "endpoint.")
    @DeleteMapping("/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID labelId) {
        Label label = labelRepository.findById(labelId)
                .filter(this::isOnMintedReferent)
                .orElseThrow(() -> new LabelNotFoundException("no label " + labelId + " on a minted referent"));
        labelRepository.delete(label);
    }

    private boolean isOnMintedReferent(Label label) {
        if (label.getGroupId() != null) {
            return isMintedGroup(label.getGroupId());
        }
        if (label.getAttributeId() != null) {
            return isMintedDefinition(label.getAttributeId());
        }
        if (label.getOptionId() != null) {
            return attributeOptionRepository.findById(label.getOptionId())
                    .map(option -> isMintedDefinition(option.getAttributeId()))
                    .orElse(false);
        }
        if (label.getSubjectId() != null) {
            return isMintedSubject(label.getSubjectId());
        }
        if (label.getTopicId() != null) {
            return isMintedTopic(label.getTopicId());
        }
        return false;
    }

    private void requireMintedReferent(LabelReferentKind kind, UUID referentId) {
        switch (kind) {
            case GROUP -> {
                if (!isMintedGroup(referentId)) {
                    throw new AttributeGroupNotFoundException();
                }
            }
            case ATTRIBUTE -> {
                if (!isMintedDefinition(referentId)) {
                    throw new AttributeDefinitionNotFoundException();
                }
            }
            case OPTION -> {
                boolean isMinted = attributeOptionRepository.findById(referentId)
                        .map(option -> isMintedDefinition(option.getAttributeId()))
                        .orElse(false);
                if (!isMinted) {
                    throw new AttributeOptionNotFoundException();
                }
            }
            case SUBJECT -> {
                if (!isMintedSubject(referentId)) {
                    throw new SubjectNotFoundException();
                }
            }
            case TOPIC -> {
                if (!isMintedTopic(referentId)) {
                    throw new TopicNotFoundException();
                }
            }
            case KIND -> throw new InvalidLabelReferentException("kind labels are seeded data, not fixtures");
        }
    }

    private List<Label> existing(LabelReferentKind kind, UUID referentId) {
        return switch (kind) {
            case GROUP -> labelRepository.findByGroupIdIn(List.of(referentId));
            case ATTRIBUTE -> labelRepository.findByAttributeIdIn(List.of(referentId));
            case OPTION -> labelRepository.findByOptionIdIn(List.of(referentId));
            case SUBJECT -> labelRepository.findBySubjectIdIn(List.of(referentId));
            case TOPIC -> labelRepository.findByTopicIdIn(List.of(referentId));
            case KIND -> List.of();
        };
    }

    private boolean isMintedGroup(UUID groupId) {
        return attributeGroupRepository.findById(groupId)
                .map(group -> DevTestFixtures.MARKER.equals(group.getDevMarker()))
                .orElse(false);
    }

    private boolean isMintedDefinition(UUID definitionId) {
        return attributeDefinitionRepository.findById(definitionId)
                .map(definition -> DevTestFixtures.MARKER.equals(definition.getDevMarker()))
                .orElse(false);
    }

    private boolean isMintedSubject(UUID subjectId) {
        return subjectRepository.findById(subjectId)
                .map(subject -> DevTestFixtures.MARKER.equals(subject.getDevMarker()))
                .orElse(false);
    }

    private boolean isMintedTopic(UUID topicId) {
        return topicRepository.findById(topicId)
                .map(topic -> DevTestFixtures.MARKER.equals(topic.getDevMarker()))
                .orElse(false);
    }
}

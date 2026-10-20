package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.exception.AttributeDefinitionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeGroupNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionsNotSupportedException;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeDefinitionRequest;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeDefinitionResponse;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeGroupRequest;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeGroupResponse;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeOptionRequest;
import com.persiangulfwiki.core.dev.dto.DevTestAttributeOptionResponse;
import com.persiangulfwiki.core.dev.exception.DevTestAttributeGroupInUseException;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
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

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Mints throwaway field groups, definitions and options, for E2E suites that need vocabulary to
// read or patch without touching the seeded rows. Same two gates and the same reasoning as
// DevTestSubjectController — see that class.
//
// Bypasses the admin services so states they refuse stay reachable: no labels at all, a
// definition or option deprecated from the start. The database CHECKs still apply, so nothing
// minted here is a state the read side could never meet.
//
// A minted option carries no marker of its own; it is disposable because its definition is, and
// it goes with it through ON DELETE CASCADE (V22). The same holds for labels on minted rows.
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Attributes", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestAttributeController {

    // Enough of the UUID to keep parallel runs from colliding on the unique key/code columns.
    private static final int SLUG_LENGTH = 12;
    private static final String FARSI = "fa";

    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final LabelRepository labelRepository;

    @Operation(summary = "Mint a disposable field group", description = "Every field of the request body is optional; "
            + "the default is a generated key, sort 0 and no label.")
    @ApiResponse(responseCode = "201", description = "The created group.")
    @PostMapping("/test-attribute-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestAttributeGroupResponse mintGroup(
            @Valid @RequestBody(required = false) DevTestAttributeGroupRequest request) {
        DevTestAttributeGroupRequest safeRequest =
                request != null ? request : new DevTestAttributeGroupRequest(null, null, null);
        AttributeGroup group = attributeGroupRepository.save(AttributeGroup.builder()
                .key(safeRequest.key() != null ? safeRequest.key() : "e2e_" + slug())
                .sort(Objects.requireNonNullElse(safeRequest.sort(), 0))
                .devMarker(DevTestFixtures.MARKER)
                .build());
        saveFarsiLabel(Label.builder().groupId(group.getId()), safeRequest.faLabel());
        return new DevTestAttributeGroupResponse(group.getId(), group.getKey());
    }

    @Operation(summary = "Delete a minted field group", description = "Deletes a group minted by this endpoint together with "
            + "the minted definitions in it. Refuses anything else — a seeded or admin-created group is reported as 404 "
            + "rather than deleted.")
    @ApiResponse(responseCode = "204", description = "The group was deleted.")
    @ApiResponse(responseCode = "404", description = "No such group, or the id names one this endpoint did not mint.")
    @ApiResponse(responseCode = "409", description = "The group still holds a definition this endpoint did not mint.")
    @DeleteMapping("/test-attribute-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteGroup(@PathVariable UUID groupId) {
        AttributeGroup group = attributeGroupRepository.findById(groupId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(AttributeGroupNotFoundException::new);
        attributeDefinitionRepository.deleteByGroupIdAndDevMarker(groupId, DevTestFixtures.MARKER);
        if (attributeDefinitionRepository.existsByGroupId(groupId)) {
            throw new DevTestAttributeGroupInUseException("group " + groupId + " still holds a non-test definition");
        }
        attributeGroupRepository.delete(group);
    }

    @Operation(summary = "Mint a disposable field definition", description = "Only `valueType` is required. Defaults: a "
            + "generated key, the first group by sort, kinds [ISLAND], every flag false and no label. Type-dependent "
            + "rules are the same as the admin endpoint's.")
    @ApiResponse(responseCode = "201", description = "The created definition.")
    @ApiResponse(responseCode = "400", description = "The body is missing `valueType` or breaks a type-dependent rule.")
    @ApiResponse(responseCode = "404", description = "No group with the given `groupId`, or no group exists at all.")
    @PostMapping("/test-attribute-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestAttributeDefinitionResponse mintDefinition(
            @Valid @RequestBody DevTestAttributeDefinitionRequest request) {
        UUID groupId = request.groupId() != null ? requireGroup(request.groupId()) : firstGroupId();
        Set<SubjectKind> kinds = request.kinds() != null && !request.kinds().isEmpty()
                ? request.kinds() : Set.of(SubjectKind.ISLAND);

        AttributeDefinition definition = AttributeDefinition.builder()
                .key(request.key() != null ? request.key() : "e2e_" + slug())
                .valueType(request.valueType())
                .unit(request.unit())
                .groupId(groupId)
                .sort(Objects.requireNonNullElse(request.sort(), 0))
                .isMulti(Boolean.TRUE.equals(request.isMulti()))
                .isRequired(Boolean.TRUE.equals(request.isRequired()))
                .isInfobox(Boolean.TRUE.equals(request.isInfobox()))
                .isFilterable(Boolean.TRUE.equals(request.isFilterable()))
                .isBadge(Boolean.TRUE.equals(request.isBadge()))
                .refKind(request.refKind())
                .minValue(request.minValue())
                .maxValue(request.maxValue())
                .isDeprecated(Boolean.TRUE.equals(request.isDeprecated()))
                .devMarker(DevTestFixtures.MARKER)
                .build();
        definition.replaceKinds(kinds);
        definition = attributeDefinitionRepository.save(definition);
        saveFarsiLabel(Label.builder().attributeId(definition.getId()), request.faLabel());
        return new DevTestAttributeDefinitionResponse(definition.getId(), definition.getKey(), groupId,
                definition.getValueType());
    }

    @Operation(summary = "Delete a minted field definition", description = "Deletes a definition minted by this endpoint, "
            + "with its options and labels. Refuses anything else — a seeded or admin-created definition is reported as "
            + "404 rather than deleted.")
    @ApiResponse(responseCode = "204", description = "The definition was deleted.")
    @ApiResponse(responseCode = "404", description = "No such definition, or the id names one this endpoint did not mint.")
    @DeleteMapping("/test-attribute-definitions/{definitionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteDefinition(@PathVariable UUID definitionId) {
        attributeDefinitionRepository.delete(requireMintedDefinition(definitionId));
    }

    @Operation(summary = "Mint a disposable option", description = "Adds an option to a definition minted by this endpoint; "
            + "seeded and admin-created definitions are refused. Every field is optional: a generated code, sort 0, not "
            + "deprecated, no label. Set `isDeprecated` to mint a retired option.")
    @ApiResponse(responseCode = "201", description = "The created option.")
    @ApiResponse(responseCode = "404", description = "No such definition, or the id names one this endpoint did not mint.")
    @ApiResponse(responseCode = "409", description = "The definition is not an ENUM.")
    @PostMapping("/test-attribute-definitions/{definitionId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestAttributeOptionResponse mintOption(@PathVariable UUID definitionId,
            @Valid @RequestBody(required = false) DevTestAttributeOptionRequest request) {
        DevTestAttributeOptionRequest safeRequest =
                request != null ? request : new DevTestAttributeOptionRequest(null, null, null, null);
        AttributeDefinition definition = requireMintedDefinition(definitionId);
        // Kept even here: an option on a non-ENUM field is not an unreachable state but an
        // invalid one, and the read side would render it as a choice the field doesn't have.
        if (definition.getValueType() != AttributeValueType.ENUM) {
            throw new AttributeOptionsNotSupportedException("definition " + definitionId + " is not ENUM");
        }

        AttributeOption option = attributeOptionRepository.save(AttributeOption.builder()
                .attributeId(definitionId)
                .code(safeRequest.code() != null ? safeRequest.code() : "E2E_" + slug().toUpperCase())
                .sort(Objects.requireNonNullElse(safeRequest.sort(), 0))
                .isDeprecated(Boolean.TRUE.equals(safeRequest.isDeprecated()))
                .build());
        saveFarsiLabel(Label.builder().optionId(option.getId()), safeRequest.faLabel());
        return new DevTestAttributeOptionResponse(option.getId(), definitionId, option.getCode(), option.isDeprecated());
    }

    @Operation(summary = "Delete a minted option", description = "Deletes an option of a definition minted by this "
            + "endpoint. Refuses anything else.")
    @ApiResponse(responseCode = "204", description = "The option was deleted.")
    @ApiResponse(responseCode = "404", description = "No such definition or option, the definition was not minted by this "
            + "endpoint, or the option belongs to another definition.")
    @DeleteMapping("/test-attribute-definitions/{definitionId}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteOption(@PathVariable UUID definitionId, @PathVariable UUID optionId) {
        requireMintedDefinition(definitionId);
        AttributeOption option = attributeOptionRepository.findByIdAndAttributeId(optionId, definitionId)
                .orElseThrow(AttributeOptionNotFoundException::new);
        attributeOptionRepository.delete(option);
    }

    // The marker check is what makes handing an arbitrary id to these routes safe — same
    // reasoning as DevTestSubjectController.delete.
    private AttributeDefinition requireMintedDefinition(UUID definitionId) {
        return attributeDefinitionRepository.findById(definitionId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(AttributeDefinitionNotFoundException::new);
    }

    private UUID requireGroup(UUID groupId) {
        if (!attributeGroupRepository.existsById(groupId)) {
            throw new AttributeGroupNotFoundException();
        }
        return groupId;
    }

    private UUID firstGroupId() {
        return attributeGroupRepository.findFirstByOrderBySortAscKeyAsc()
                .map(AttributeGroup::getId)
                .orElseThrow(AttributeGroupNotFoundException::new);
    }

    private void saveFarsiLabel(Label.LabelBuilder<?, ?> referent, @Nullable String value) {
        if (value != null) {
            labelRepository.save(referent.language(FARSI).value(value).build());
        }
    }

    private static String slug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
    }
}

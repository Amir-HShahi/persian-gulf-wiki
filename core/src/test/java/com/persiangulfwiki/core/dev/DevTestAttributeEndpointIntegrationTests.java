package com.persiangulfwiki.core.dev;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev profile only, like DevTestContentEndpointIntegrationTests. Minted fields are attached to
// FISHING_GROUND (or the ISLAND default), never SPECIES, whose seeded vocabulary
// AttributeFlowIntegrationTests pins down exactly.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestAttributeEndpointIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AttributeGroupRepository attributeGroupRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeOptionRepository attributeOptionRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private DevTestAttributeSweeper attributeSweeper;

    @Test
    void mintsAMarkedDefinitionOfEveryValueType() throws Exception {
        for (AttributeValueType valueType : AttributeValueType.values()) {
            String refKind = valueType == AttributeValueType.SUBJECT_REF ? ", \"refKind\": \"PROVINCE\"" : "";
            String body = mintDefinition("{\"valueType\": \"%s\"%s}".formatted(valueType, refKind))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.valueType").value(valueType.name()))
                    .andExpect(jsonPath("$.key", startsWith("e2e_")))
                    .andReturn().getResponse().getContentAsString();

            AttributeDefinition minted = attributeDefinitionRepository
                    .findById(UUID.fromString(JsonPath.read(body, "$.definitionId"))).orElseThrow();
            assertThat(minted.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
        }
    }

    @Test
    void mintsDefinitionsWithTypeRulesStillEnforced() throws Exception {
        mintDefinition("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("valueType")));
        mintDefinition("{\"valueType\": \"PLAIN_TEXT\", \"unit\": \"cm\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("unit")));
    }

    // Every label state the read side handles: a missing English label (fallback), no label at
    // all, and a deprecated option — none reachable through the admin endpoints.
    @Test
    void mintedVocabularyIsReadableInEveryLabelState() throws Exception {
        String group = mintGroup("{\"sort\": 990, \"faLabel\": \"گروه آزمایشی\"}");
        String groupId = JsonPath.read(group, "$.groupId");
        String groupKey = JsonPath.read(group, "$.key");
        String definition = mintDefinition("""
                {"valueType": "ENUM", "groupId": "%s", "kinds": ["FISHING_GROUND"], "isFilterable": true,
                 "faLabel": "ویژگی آزمایشی"}""".formatted(groupId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String definitionId = JsonPath.read(definition, "$.definitionId");
        mintOption(definitionId, "{\"code\": \"LIVE_ONE\", \"sort\": 1, \"faLabel\": \"زنده\"}")
                .andExpect(status().isCreated());
        mintOption(definitionId, "{\"code\": \"RETIRED_ONE\", \"sort\": 2, \"isDeprecated\": true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDeprecated").value(true));
        mintLabel("ATTRIBUTE", definitionId, "en", "Test field")
                .andExpect(status().isCreated());

        String groupPath = "$.data.groups[?(@.key == '" + groupKey + "')]";
        mockMvc.perform(get("/api/subject-kinds/fishing_ground/attributes").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(groupPath + ".label.isFallback").value(contains(true)))
                .andExpect(jsonPath(groupPath + ".attributes[0].label.value").value(contains("Test field")))
                .andExpect(jsonPath(groupPath + ".attributes[0].options[*].code").value(contains("LIVE_ONE", "RETIRED_ONE")))
                .andExpect(jsonPath(groupPath + ".attributes[0].options[0].label.value").value(contains("زنده")))
                .andExpect(jsonPath(groupPath + ".attributes[0].options[1].isDeprecated").value(contains(true)))
                .andExpect(jsonPath(groupPath + ".attributes[0].options[1].label").value(contains(nullValue())));
    }

    @Test
    void deletingAMintedDefinitionTakesItsOptionsAndLabels() throws Exception {
        String definitionId = JsonPath.read(mintDefinition("{\"valueType\": \"ENUM\", \"faLabel\": \"x\"}")
                .andReturn().getResponse().getContentAsString(), "$.definitionId");
        String optionId = JsonPath.read(mintOption(definitionId, "{\"faLabel\": \"y\"}")
                .andReturn().getResponse().getContentAsString(), "$.optionId");

        mockMvc.perform(delete("/api/dev/test-attribute-definitions/{id}", definitionId))
                .andExpect(status().isNoContent());

        assertThat(attributeDefinitionRepository.findById(UUID.fromString(definitionId))).isEmpty();
        assertThat(attributeOptionRepository.findById(UUID.fromString(optionId))).isEmpty();
        assertThat(labelRepository.findByAttributeIdIn(List.of(UUID.fromString(definitionId)))).isEmpty();
        assertThat(labelRepository.findByOptionIdIn(List.of(UUID.fromString(optionId)))).isEmpty();
        mockMvc.perform(delete("/api/dev/test-attribute-definitions/{id}", definitionId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAMintedGroupTakesItsMintedDefinitions() throws Exception {
        String groupId = JsonPath.read(mintGroup("{}"), "$.groupId");
        String definitionId = JsonPath.read(mintDefinition("{\"valueType\": \"BOOL\", \"groupId\": \"%s\"}"
                .formatted(groupId)).andReturn().getResponse().getContentAsString(), "$.definitionId");

        mockMvc.perform(delete("/api/dev/test-attribute-groups/{id}", groupId))
                .andExpect(status().isNoContent());

        assertThat(attributeGroupRepository.findById(UUID.fromString(groupId))).isEmpty();
        assertThat(attributeDefinitionRepository.findById(UUID.fromString(definitionId))).isEmpty();
    }

    @Test
    void mintedGroupHoldingARealDefinitionIsRefusedAndSkippedBySweeper() throws Exception {
        UUID groupId = UUID.fromString(JsonPath.read(mintGroup("{}"), "$.groupId"));
        AttributeDefinition real = saveUnmarkedDefinition(groupId);
        try {
            mockMvc.perform(delete("/api/dev/test-attribute-groups/{id}", groupId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEV_TEST_ATTRIBUTE_GROUP_IN_USE"));

            attributeSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));

            assertThat(attributeGroupRepository.findById(groupId)).isPresent();
            assertThat(attributeDefinitionRepository.findById(real.getId())).isPresent();
        } finally {
            attributeDefinitionRepository.deleteById(real.getId());
            attributeGroupRepository.deleteById(groupId);
        }
    }

    @Test
    void refusesToTouchSeededVocabulary() throws Exception {
        UUID population = seededDefinitionId("population");
        UUID country = seededDefinitionId("country");
        UUID geography = seededGroupId("geography");
        Label seededLabel = labelRepository.findByAttributeIdIn(List.of(population)).getFirst();

        mockMvc.perform(delete("/api/dev/test-attribute-definitions/{id}", population))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_DEFINITION_NOT_FOUND"));
        mockMvc.perform(delete("/api/dev/test-attribute-groups/{id}", geography))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_GROUP_NOT_FOUND"));
        mintOption(country.toString(), "{}")
                .andExpect(status().isNotFound());
        mintLabel("ATTRIBUTE", population.toString(), "en", "overwritten")
                .andExpect(status().isNotFound());
        mintLabel("KIND", UUID.randomUUID().toString(), "en", "overwritten")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL_REFERENT"));
        mockMvc.perform(delete("/api/dev/test-labels/{id}", seededLabel.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LABEL_NOT_FOUND"));

        assertThat(attributeDefinitionRepository.findById(population)).isPresent();
        assertThat(attributeGroupRepository.findById(geography)).isPresent();
        assertThat(labelRepository.findById(seededLabel.getId())).get()
                .extracting(Label::getValue).isEqualTo(seededLabel.getValue());
    }

    @Test
    void optionOnANonEnumDefinitionIsRefused() throws Exception {
        String definitionId = JsonPath.read(mintDefinition("{\"valueType\": \"NUMBER\"}")
                .andReturn().getResponse().getContentAsString(), "$.definitionId");

        mintOption(definitionId, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_OPTIONS_NOT_SUPPORTED"));
    }

    @Test
    void labelsOnAMintedSubjectCanBeSetReplacedAndDeleted() throws Exception {
        String subject = mockMvc.perform(post("/api/dev/test-subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"STRAIT\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String subjectId = JsonPath.read(subject, "$.subjectId");

        String first = mintLabel("SUBJECT", subjectId, "fa", "تنگهٔ آزمایشی")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String labelId = JsonPath.read(first, "$.labelId");
        mintLabel("SUBJECT", subjectId, "fa", "تنگهٔ دیگر")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.labelId").value(labelId))
                .andExpect(jsonPath("$.value").value("تنگهٔ دیگر"));

        mockMvc.perform(delete("/api/dev/test-labels/{id}", labelId))
                .andExpect(status().isNoContent());
        assertThat(labelRepository.findById(UUID.fromString(labelId))).isEmpty();
    }

    @Test
    void sweeperReclaimsAgedMintedVocabularyAndSparesSeededRows() throws Exception {
        String groupId = JsonPath.read(mintGroup("{\"faLabel\": \"x\"}"), "$.groupId");
        String inMintedGroup = JsonPath.read(mintDefinition("{\"valueType\": \"DATE\", \"groupId\": \"%s\"}"
                .formatted(groupId)).andReturn().getResponse().getContentAsString(), "$.definitionId");
        String inSeededGroup = JsonPath.read(mintDefinition("{\"valueType\": \"DATE\"}")
                .andReturn().getResponse().getContentAsString(), "$.definitionId");

        // Nothing is older than an hour ago, so this sweep must leave the fresh rows alone.
        attributeSweeper.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(attributeDefinitionRepository.findById(UUID.fromString(inSeededGroup))).isPresent();

        long deleted = attributeSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(attributeGroupRepository.findById(UUID.fromString(groupId))).isEmpty();
        assertThat(attributeDefinitionRepository.findById(UUID.fromString(inMintedGroup))).isEmpty();
        assertThat(attributeDefinitionRepository.findById(UUID.fromString(inSeededGroup))).isEmpty();
        assertThat(attributeDefinitionRepository.findById(seededDefinitionId("population"))).isPresent();
        assertThat(attributeGroupRepository.findById(seededGroupId("geography"))).isPresent();
    }

    private String mintGroup(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-attribute-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions mintDefinition(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-attribute-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions mintOption(String definitionId, String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-attribute-definitions/{id}/options", definitionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions mintLabel(String referentKind, String referentId, String language, String value)
            throws Exception {
        return mockMvc.perform(post("/api/dev/test-labels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"referentKind": "%s", "referentId": "%s", "language": "%s", "value": "%s"}"""
                        .formatted(referentKind, referentId, language, value)));
    }

    // A real (unmarked) definition, which no admin route can put in a minted group without
    // knowing its id — so it is written directly.
    private AttributeDefinition saveUnmarkedDefinition(UUID groupId) {
        AttributeDefinition definition = AttributeDefinition.builder()
                .key("real_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .valueType(AttributeValueType.BOOL)
                .groupId(groupId)
                .build();
        definition.replaceKinds(Set.of(SubjectKind.FISHING_GROUND));
        return attributeDefinitionRepository.save(definition);
    }

    private UUID seededDefinitionId(String key) {
        return attributeDefinitionRepository.findAll().stream()
                .filter(definition -> definition.getKey().equals(key))
                .map(AttributeDefinition::getId)
                .findFirst()
                .orElseThrow();
    }

    private UUID seededGroupId(String key) {
        return attributeGroupRepository.findAll().stream()
                .filter(group -> group.getKey().equals(key))
                .map(AttributeGroup::getId)
                .findFirst()
                .orElseThrow();
    }
}

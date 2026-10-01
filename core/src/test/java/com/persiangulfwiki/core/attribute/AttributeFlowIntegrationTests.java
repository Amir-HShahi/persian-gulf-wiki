package com.persiangulfwiki.core.attribute;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fields created here are attached to PROVINCE only, so the seeded SPECIES vocabulary that the
// read tests pin down exactly stays untouched by the write tests, whatever order they run in.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AttributeFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String SPECIES_PATH = "/api/subject-kinds/species/attributes";
    private static final String PROVINCE_PATH = "/api/subject-kinds/province/attributes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private AttributeGroupRepository attributeGroupRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // --- public read ------------------------------------------------------------------------

    @Test
    void anonymousReadsSeededSpeciesVocabularyInEnglish() throws Exception {
        mockMvc.perform(get(SPECIES_PATH).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("SPECIES"))
                .andExpect(jsonPath("$.data.label.value").value("Species"))
                .andExpect(jsonPath("$.data.label.isFallback").value(false))
                .andExpect(jsonPath("$.data.groups[*].key")
                        .value(contains("classification", "conservation", "ecology", "morphology")))
                .andExpect(jsonPath("$.data.groups[0].attributes[*].key").value(contains("scientific_name", "category")))
                .andExpect(jsonPath("$.data.groups[0].attributes[0].valueType").value("PLAIN_TEXT"))
                .andExpect(jsonPath("$.data.groups[0].attributes[0].isRequired").value(true))
                .andExpect(jsonPath("$.data.groups[0].attributes[0].isFilterable").value(false))
                .andExpect(jsonPath("$.data.groups[0].attributes[0].required").doesNotExist())
                .andExpect(jsonPath("$.data.groups[1].label.value").value("Conservation"))
                .andExpect(jsonPath("$.data.groups[1].attributes[0].options[*].code")
                        .value(contains("CR", "EN", "VU", "NT", "LC", "DD")))
                .andExpect(jsonPath("$.data.groups[1].attributes[0].options[0].label.value")
                        .value("Critically endangered"))
                .andExpect(jsonPath("$.data.groups[2].attributes[*].key").value(contains("habitat", "species_features")))
                .andExpect(jsonPath("$.data.groups[2].attributes[0].isMulti").value(true))
                .andExpect(jsonPath("$.data.groups[3].attributes[0].key").value("max_length"))
                .andExpect(jsonPath("$.data.groups[3].attributes[0].unit").value("cm"));
    }

    @Test
    void readWithoutOrWithUnsupportedLanguageIsFarsi() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get(SPECIES_PATH), get(SPECIES_PATH).header(HttpHeaders.ACCEPT_LANGUAGE, "de"))) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.label.value").value("گونه‌ها"))
                    .andExpect(jsonPath("$.data.label.language").value("fa"))
                    .andExpect(jsonPath("$.data.label.isFallback").value(false));
        }
    }

    @Test
    void placeKindsShareFieldsWhereTheyApply() throws Exception {
        mockMvc.perform(get("/api/subject-kinds/ISLAND/attributes").header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[*].key")
                        .value(hasItem("administrative_divisions")))
                .andExpect(jsonPath("$..attributes[?(@.key == 'country')].isMulti").value(contains(true)))
                .andExpect(jsonPath("$..attributes[?(@.key == 'country')].options[*].code")
                        .value(contains("IR", "IQ", "KW", "SA", "BH", "QA", "AE", "OM")))
                .andExpect(jsonPath("$..attributes[?(@.key == 'province')].refKind").value(contains("PROVINCE")))
                .andExpect(jsonPath("$..attributes[?(@.key == 'located_in')].refKind").value(contains("STRAIT")))
                .andExpect(jsonPath("$..attributes[?(@.key == 'population')].label.value").value(contains("عدد السكان")));

        mockMvc.perform(get("/api/subject-kinds/strait/attributes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..attributes[*].key").value(hasItem("country")))
                .andExpect(jsonPath("$..attributes[*].key").value(not(hasItem("province"))))
                .andExpect(jsonPath("$..attributes[*].key").value(not(hasItem("located_in"))));
    }

    @Test
    void unknownKindIsRejected() throws Exception {
        mockMvc.perform(get("/api/subject-kinds/sea_monster/attributes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
    }

    // The route is in PublicRoutes, so the restricted-session filters treat an unverified cookie
    // as anonymous instead of answering 403.
    @Test
    void unverifiedSessionCanStillReadVocabulary() throws Exception {
        registerContributor("atfunverified", "atf-unverified@example.com");
        Cookie accessCookie = loginAccessCookie("atf-unverified@example.com");

        mockMvc.perform(get(SPECIES_PATH).cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("SPECIES"));
    }

    // --- admin writes -----------------------------------------------------------------------

    @Test
    void adminDefinesAFieldAndReadersSeeItWithFarsiFallback() throws Exception {
        User admin = seedAdmin("atfadmin1", "atf-admin1@example.com");
        Cookie accessCookie = loginAccessCookie("atf-admin1@example.com");
        String groupKey = uniqueKey();
        String fieldKey = uniqueKey();

        String group = adminWrite(post("/api/admin/attribute-groups"), accessCookie, """
                {"key": "%s", "sort": 900, "labels": {"fa": "گروه آزمایشی"}}""".formatted(groupKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key").value(groupKey))
                .andExpect(jsonPath("$.data.labels.fa").value("گروه آزمایشی"))
                .andExpect(jsonPath("$.data.labels.en").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(group, "$.data.id");

        String definition = adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "%s", "valueType": "ENUM", "groupId": "%s", "isMulti": true, "isInfobox": true,
                 "isFilterable": true, "kinds": ["PROVINCE"],
                 "labels": {"fa": "ویژگی آزمایشی", "en": "Test field"}}""".formatted(fieldKey, groupId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.valueType").value("ENUM"))
                .andExpect(jsonPath("$.data.isMulti").value(true))
                .andExpect(jsonPath("$.data.isRequired").value(false))
                .andExpect(jsonPath("$.data.kinds").value(contains("PROVINCE")))
                .andExpect(jsonPath("$.data.labels.en").value("Test field"))
                .andReturn().getResponse().getContentAsString();
        String definitionId = JsonPath.read(definition, "$.data.id");

        adminWrite(post("/api/admin/attribute-definitions/" + definitionId + "/options"), accessCookie, """
                {"code": "ALPHA", "sort": 10, "labels": {"fa": "الف"}}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("ALPHA"))
                .andExpect(jsonPath("$.data.isDeprecated").value(false));

        String groupPath = "$.data.groups[?(@.key == '" + groupKey + "')]";
        mockMvc.perform(get(PROVINCE_PATH).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(groupPath + ".label.value").value(contains("گروه آزمایشی")))
                .andExpect(jsonPath(groupPath + ".label.language").value(contains("fa")))
                .andExpect(jsonPath(groupPath + ".label.isFallback").value(contains(true)))
                .andExpect(jsonPath(groupPath + ".attributes[0].label.value").value(contains("Test field")))
                .andExpect(jsonPath(groupPath + ".attributes[0].label.isFallback").value(contains(false)))
                .andExpect(jsonPath(groupPath + ".attributes[0].options[0].label.isFallback").value(contains(true)));

        // Adding the English label clears the fallback.
        adminWrite(put("/api/admin/labels/GROUP/" + groupId + "/en"), accessCookie, """
                {"value": "Test group"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referentKind").value("GROUP"))
                .andExpect(jsonPath("$.data.referentId").value(groupId))
                .andExpect(jsonPath("$.data.value").value("Test group"));
        mockMvc.perform(get(PROVINCE_PATH).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath(groupPath + ".label.value").value(contains("Test group")))
                .andExpect(jsonPath(groupPath + ".label.isFallback").value(contains(false)));

        assertThat(auditLogRepository.findAll())
                .filteredOn(entry -> admin.getId().equals(entry.getActorUserId()))
                .extracting(AuditLog::getAction)
                .contains("ATTRIBUTE_GROUP_CREATE", "ATTRIBUTE_DEFINITION_CREATE", "ATTRIBUTE_OPTION_CREATE",
                        "LABEL_UPSERT");
    }

    @Test
    void definitionTypeRulesAreReportedAgainstTheFieldToChange() throws Exception {
        seedAdmin("atfadmin2", "atf-admin2@example.com");
        Cookie accessCookie = loginAccessCookie("atf-admin2@example.com");
        UUID groupId = seededGroupId("geography");

        adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "%s", "valueType": "PLAIN_TEXT", "unit": "cm", "refKind": "ISLAND", "minValue": 5,
                 "isFilterable": true, "groupId": "%s", "kinds": ["PROVINCE"], "labels": {"fa": "x"}}"""
                .formatted(uniqueKey(), groupId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("refKind", "unit", "minValue", "isFilterable")));

        adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "%s", "valueType": "NUMBER", "minValue": 10, "maxValue": 5, "groupId": "%s",
                 "kinds": ["PROVINCE"], "labels": {"fa": "x"}}""".formatted(uniqueKey(), groupId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("maxValue")));

        adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "Bad-Key", "valueType": "SUBJECT_REF", "groupId": "%s", "kinds": [],
                 "labels": {"fa": " ", "en": " "}}""".formatted(groupId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("key", "refKind", "kinds", "labels.fa", "labels.en")));
    }

    @Test
    void duplicateKeysAndCodesConflict() throws Exception {
        seedAdmin("atfadmin3", "atf-admin3@example.com");
        Cookie accessCookie = loginAccessCookie("atf-admin3@example.com");
        UUID groupId = seededGroupId("geography");

        adminWrite(post("/api/admin/attribute-groups"), accessCookie, """
                {"key": "geography", "labels": {"fa": "x"}}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ATTRIBUTE_GROUP_KEY"));

        adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "population", "valueType": "NUMBER", "groupId": "%s", "kinds": ["PROVINCE"],
                 "labels": {"fa": "x"}}""".formatted(groupId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ATTRIBUTE_KEY"));

        String fieldId = createDefinition(accessCookie, uniqueKey(), "ENUM", groupId);
        String option = """
                {"code": "BETA", "labels": {"fa": "ب"}}""";
        adminWrite(post("/api/admin/attribute-definitions/" + fieldId + "/options"), accessCookie, option)
                .andExpect(status().isCreated());
        adminWrite(post("/api/admin/attribute-definitions/" + fieldId + "/options"), accessCookie, option)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ATTRIBUTE_OPTION_CODE"));
    }

    @Test
    void definitionAndOptionUpdatesReplaceStateAndGuardTheValueType() throws Exception {
        seedAdmin("atfadmin4", "atf-admin4@example.com");
        Cookie accessCookie = loginAccessCookie("atf-admin4@example.com");
        UUID groupId = seededGroupId("geography");

        String numberId = createDefinition(accessCookie, uniqueKey(), "NUMBER", groupId);
        adminWrite(post("/api/admin/attribute-definitions/" + numberId + "/options"), accessCookie, """
                {"code": "GAMMA", "labels": {"fa": "ج"}}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_OPTIONS_NOT_SUPPORTED"));

        // Full replacement: omitting `unit` clears it.
        adminWrite(patch("/api/admin/attribute-definitions/" + numberId), accessCookie, """
                {"valueType": "NUMBER", "unit": "km2", "groupId": "%s", "kinds": ["PROVINCE"]}""".formatted(groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value("km2"));
        adminWrite(patch("/api/admin/attribute-definitions/" + numberId), accessCookie, """
                {"valueType": "NUMBER", "groupId": "%s", "kinds": ["PROVINCE"]}""".formatted(groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").doesNotExist());

        String enumKey = uniqueKey();
        String enumId = createDefinition(accessCookie, enumKey, "ENUM", groupId);
        String created = adminWrite(post("/api/admin/attribute-definitions/" + enumId + "/options"), accessCookie, """
                {"code": "DELTA", "labels": {"fa": "د"}}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String optionId = JsonPath.read(created, "$.data.id");

        adminWrite(patch("/api/admin/attribute-definitions/" + enumId), accessCookie, """
                {"valueType": "NUMBER", "groupId": "%s", "kinds": ["PROVINCE"]}""".formatted(groupId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_HAS_OPTIONS"));

        adminWrite(patch("/api/admin/attribute-definitions/" + enumId), accessCookie, """
                {"valueType": "ENUM", "groupId": "%s", "kinds": ["PROVINCE", "PORT"], "isDeprecated": true}"""
                .formatted(groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeprecated").value(true))
                .andExpect(jsonPath("$.data.kinds").value(contains("PORT", "PROVINCE")));
        adminWrite(patch("/api/admin/attribute-definitions/" + enumId + "/options/" + optionId), accessCookie, """
                {"sort": 3, "isDeprecated": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeprecated").value(true))
                .andExpect(jsonPath("$.data.sort").value(3));

        // An option id that exists, but under another definition.
        adminWrite(patch("/api/admin/attribute-definitions/" + numberId + "/options/" + optionId), accessCookie, """
                {"sort": 1}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_OPTION_NOT_FOUND"));

        String fieldPath = "$..attributes[?(@.key == '" + enumKey + "')]";
        mockMvc.perform(get("/api/subject-kinds/port/attributes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(fieldPath + ".isDeprecated").value(contains(true)))
                .andExpect(jsonPath(fieldPath + ".options[0].isDeprecated").value(contains(true)));
    }

    @Test
    void labelWritesRejectSubjectsUnknownLanguagesAndMissingReferents() throws Exception {
        seedAdmin("atfadmin5", "atf-admin5@example.com");
        Cookie accessCookie = loginAccessCookie("atf-admin5@example.com");
        UUID groupId = seededGroupId("geography");
        String value = """
                {"value": "x"}""";

        adminWrite(put("/api/admin/labels/SUBJECT/" + UUID.randomUUID() + "/en"), accessCookie, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL_REFERENT"));
        adminWrite(put("/api/admin/labels/GROUP/not-a-uuid/en"), accessCookie, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL_REFERENT"));
        adminWrite(put("/api/admin/labels/KIND/SEA_MONSTER/en"), accessCookie, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL_REFERENT"));
        adminWrite(put("/api/admin/labels/GROUP/" + groupId + "/de"), accessCookie, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_LABEL_LANGUAGE"));
        adminWrite(put("/api/admin/labels/ATTRIBUTE/" + UUID.randomUUID() + "/en"), accessCookie, value)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_DEFINITION_NOT_FOUND"));
        adminWrite(put("/api/admin/labels/GROUP/" + groupId + "/en"), accessCookie, """
                {"value": "  "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Rewritten with its seeded value, so the shared vocabulary is left as it was.
        adminWrite(put("/api/admin/labels/kind/province/EN"), accessCookie, """
                {"value": "Provinces"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referentKind").value("KIND"))
                .andExpect(jsonPath("$.data.referentId").value("PROVINCE"))
                .andExpect(jsonPath("$.data.language").value("en"));
    }

    @Test
    void vocabularyWritesAreAdminOnlyAndCsrfProtected() throws Exception {
        String body = """
                {"key": "%s", "labels": {"fa": "x"}}""".formatted(uniqueKey());

        mockMvc.perform(post("/api/admin/attribute-groups").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        User moderator = registerVerifiedContributor("atfmod1", "atf-mod1@example.com");
        userRoleRepository.save(UserRole.builder().user(moderator).role(Role.MODERATOR).build());
        Cookie moderatorCookie = loginAccessCookie("atf-mod1@example.com");
        adminWrite(post("/api/admin/attribute-groups"), moderatorCookie, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        adminWrite(put("/api/admin/labels/KIND/ISLAND/en"), moderatorCookie, """
                {"value": "x"}""")
                .andExpect(status().isForbidden());

        seedAdmin("atfadmin6", "atf-admin6@example.com");
        Cookie adminCookie = loginAccessCookie("atf-admin6@example.com");
        mockMvc.perform(post("/api/admin/attribute-groups").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // --- helpers ----------------------------------------------------------------------------

    private String createDefinition(Cookie accessCookie, String key, String valueType, UUID groupId)
            throws Exception {
        String created = adminWrite(post("/api/admin/attribute-definitions"), accessCookie, """
                {"key": "%s", "valueType": "%s", "groupId": "%s", "kinds": ["PROVINCE"], "labels": {"fa": "x"}}"""
                .formatted(key, valueType, groupId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

    private ResultActions adminWrite(MockHttpServletRequestBuilder request, Cookie accessCookie, String json)
            throws Exception {
        return mockMvc.perform(request.cookie(accessCookie).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private UUID seededGroupId(String key) {
        return attributeGroupRepository.findAll().stream()
                .filter(group -> group.getKey().equals(key))
                .map(AttributeGroup::getId)
                .findFirst()
                .orElseThrow();
    }

    private static String uniqueKey() {
        return "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void registerContributor(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
                .andExpect(status().isCreated());
    }

    private User registerVerifiedContributor(String username, String email) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User seedAdmin(String username, String email) throws Exception {
        User user = registerVerifiedContributor(username, email);
        userRoleRepository.save(UserRole.builder().user(user).role(Role.ADMIN).build());
        return user;
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }
}

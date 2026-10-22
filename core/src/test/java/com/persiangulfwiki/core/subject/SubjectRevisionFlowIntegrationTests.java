package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.entity.SubjectAlias;
import com.persiangulfwiki.core.subject.entity.SubjectFact;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.repository.SubjectAliasRepository;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 3: subjects change only through moderated revisions. Proposals create subjects, edits
// start from the live revision, and approval rewrites the read model (labels, aliases, facts,
// geometry, live pointer) in one transaction. Helpers first, then the new behavior, then
// regression coverage for the moderation and attribute paths this phase touched.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectRevisionFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SubjectRevisionRepository subjectRevisionRepository;

    @Autowired
    private SubjectLiveRevisionRepository subjectLiveRevisionRepository;

    @Autowired
    private SubjectFactRepository subjectFactRepository;

    @Autowired
    private SubjectAliasRepository subjectAliasRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeGroupRepository attributeGroupRepository;

    @Autowired
    private AttributeOptionRepository attributeOptionRepository;

    // --- Helpers ---

    private record Session(UUID userId, Cookie access, Cookie csrf) {
    }

    private Session signIn(String name, @Nullable Role role) throws Exception {
        String email = name + "@srf.example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(name, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        if (role != null) {
            userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        }
        Cookie access = mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        Cookie csrf = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        return new Session(user.getId(), access, csrf);
    }

    private Session contributor(String name) throws Exception {
        return signIn(name, null);
    }

    private Session moderator(String name) throws Exception {
        return signIn(name, Role.MODERATOR);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Session session, @Nullable String json)
            throws Exception {
        request.cookie(session.access(), session.csrf())
                .header("X-XSRF-TOKEN", maskCsrfToken(session.csrf().getValue()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions read(String path, @Nullable Session session) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (session != null) {
            request.cookie(session.access());
        }
        return mockMvc.perform(request);
    }

    private ResultActions proposeRaw(Session author, String kind, String payload) throws Exception {
        return send(post("/api/subject-revisions"), author, """
                {"kind": "%s", "summary": "new subject", "payload": %s}""".formatted(kind, payload));
    }

    private UUID propose(Session author, String kind, String payload) throws Exception {
        String body = proposeRaw(author, kind, payload)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private ResultActions update(Session author, UUID revisionId, String payload) throws Exception {
        return send(patch("/api/subject-revisions/" + revisionId), author, """
                {"summary": "edited", "payload": %s}""".formatted(payload));
    }

    private ResultActions submit(Session author, UUID revisionId) throws Exception {
        return send(post("/api/subject-revisions/" + revisionId + "/submit"), author, null);
    }

    private UUID startEdit(Session author, UUID subjectId) throws Exception {
        String body = send(post("/api/subjects/" + subjectId + "/revisions"), author, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private UUID taskOf(UUID revisionId) {
        return moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow().getId();
    }

    private ResultActions decide(Session moderator, UUID revisionId, Decision decision, @Nullable String reason)
            throws Exception {
        UUID taskId = taskOf(revisionId);
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        return send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(decision, reason)));
    }

    private UUID subjectOf(UUID revisionId) {
        return subjectRevisionRepository.findById(revisionId).orElseThrow().getSubjectId();
    }

    // Proposes, submits and approves, returning the new subject's id.
    private UUID approvedSubject(Session author, Session moderator, String kind, String payload) throws Exception {
        UUID revisionId = propose(author, kind, payload);
        submit(author, revisionId).andExpect(status().isOk());
        decide(moderator, revisionId, Decision.APPROVE, null).andExpect(status().isOk());
        return subjectOf(revisionId);
    }

    private UUID seedSource(String title) {
        return sourceRepository.save(Source.builder().title(title).build()).getId();
    }

    private UUID seedSubject(SubjectKind kind) {
        return subjectRepository.save(Subject.builder().kind(kind).build()).getId();
    }

    private static String islandPayload(String faName) {
        return """
                {"names": {"fa": "%s"}}""".formatted(faName);
    }

    // A field of its own, so a test can deprecate or redefine it without touching the seeded
    // vocabulary every other test reads. Never required: that would fail every other test's
    // proposals of the same kinds.
    private AttributeDefinition seedEnumField(String key, boolean isMulti, Set<SubjectKind> kinds, String... codes) {
        AttributeDefinition definition = AttributeDefinition.builder()
                .key(key)
                .valueType(AttributeValueType.ENUM)
                .groupId(attributeGroupRepository.findFirstByOrderBySortAscKeyAsc().orElseThrow().getId())
                .isMulti(isMulti)
                .build();
        definition.replaceKinds(kinds);
        definition = attributeDefinitionRepository.save(definition);
        for (String code : codes) {
            attributeOptionRepository.save(AttributeOption.builder()
                    .attributeId(definition.getId()).code(code).build());
        }
        return definition;
    }

    private static String uniqueKey(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private List<SubjectFact> factsOf(UUID subjectId) {
        return subjectFactRepository.findAll().stream()
                .filter(fact -> fact.getSubjectId().equals(subjectId))
                .toList();
    }

    private List<String> labelLanguagesOf(UUID subjectId) {
        return labelRepository.findBySubjectIdIn(List.of(subjectId)).stream().map(Label::getLanguage).sorted().toList();
    }

    // --- Smoke: the new behavior this phase adds ---

    // The whole proposal flow: a contributor's draft becomes PENDING with a task, the moderator
    // sees what it changes and which source backs each new value, and approval creates the
    // subject with every part of the payload written to the read model -- readable by a caller
    // with no account.
    @Test
    void approvedProposalCreatesSubjectAndWritesTheReadModel() throws Exception {
        Session author = contributor("srfauth1");
        Session moderator = moderator("srfmod1");
        UUID sourceId = seedSource("Census of Iran 2016");
        UUID provinceId = seedSubject(SubjectKind.PROVINCE);

        String payload = """
                {"names": {"fa": "جزیرهٔ قشم", "en": "Qeshm Island"},
                 "aliases": {"fa": ["قشم"], "en": ["Qeshm"]},
                 "facts": [
                   {"attribute": "population", "values": [
                      {"number": 148993, "asOf": "2016-01-01", "sourceId": "%1$s"},
                      {"number": 117774, "asOf": "2011-01-01", "sourceId": "%1$s", "rank": "NORMAL"}]},
                   {"attribute": "place_features", "values": [{"option": "FREE_ZONE"}, {"option": "UNESCO_GEOPARK"}]},
                   {"attribute": "province", "values": [{"subjectId": "%2$s"}]}],
                 "geometry": "POINT(55.9 26.8)"}""".formatted(sourceId, provinceId);

        String created = proposeRaw(author, "ISLAND", payload)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.subjectId").doesNotExist())
                .andExpect(jsonPath("$.data.revisionNumber").value(1))
                .andExpect(jsonPath("$.data.payload.facts[0].values[0].rank").value("PREFERRED"))
                .andExpect(jsonPath("$.data.payload.facts[0].values[1].rank").value("NORMAL"))
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(created, "$.data.id"));

        submit(author, revisionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        ModerationTask task = moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow();
        assertThat(task.getState()).isEqualTo(ModerationTaskState.OPEN);

        read("/api/moderation/tasks/" + task.getId(), moderator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.subjectRevisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.mediaReview").doesNotExist())
                .andExpect(jsonPath("$.data.subjectReview.liveRevisionNumber").doesNotExist())
                .andExpect(jsonPath("$.data.subjectReview.changes[*].field")
                        .value(hasItems("names.fa", "names.en", "aliases.fa", "facts.population",
                                "facts.place_features", "facts.province", "geometry")))
                .andExpect(jsonPath("$.data.subjectReview.sources[*].id").value(hasItem(sourceId.toString())));

        decide(moderator, revisionId, Decision.APPROVE, null).andExpect(status().isOk());

        UUID subjectId = subjectOf(revisionId);
        assertThat(subjectId).isNotNull();
        assertThat(subjectRevisionRepository.findById(revisionId).orElseThrow().getStatus())
                .isEqualTo(SubjectRevisionStatus.APPROVED);
        assertThat(subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId())
                .isEqualTo(revisionId);
        assertThat(labelLanguagesOf(subjectId)).containsExactly("en", "fa");
        assertThat(subjectAliasRepository.findBySubjectId(subjectId)).extracting(SubjectAlias::getValue)
                .containsExactlyInAnyOrder("قشم", "Qeshm");
        List<SubjectFact> facts = factsOf(subjectId);
        assertThat(facts).hasSize(5).allSatisfy(fact -> assertThat(fact.getRevisionId()).isEqualTo(revisionId));
        assertThat(facts).filteredOn(fact -> provinceId.equals(fact.getValueRefSubjectId())).hasSize(1);

        read("/api/subjects/" + subjectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.location", containsString("55.9")));
        read("/api/subjects/" + subjectId + "/revisions", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"));
        read("/api/subject-revisions/" + revisionId, null).andExpect(status().isOk());
    }

    // An edit starts as a copy of the live payload. Approving it replaces the read model as a
    // whole: a name or alias left out is removed, not kept.
    @Test
    void editStartsFromLiveRevisionAndApprovalReplacesTheReadModel() throws Exception {
        Session author = contributor("srfauth2");
        Session editor = contributor("srfedit2");
        Session moderator = moderator("srfmod2");
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", """
                {"names": {"fa": "هنگام", "en": "Hengam"}, "aliases": {"en": ["Hengam Island"]},
                 "facts": [{"attribute": "place_features", "values": [{"option": "FREE_ZONE"}]}]}""");
        UUID liveRevisionId = subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId();

        UUID editId = startEdit(editor, subjectId);
        read("/api/subject-revisions/" + editId, editor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.revisionNumber").value(2))
                .andExpect(jsonPath("$.data.baseRevisionId").value(liveRevisionId.toString()))
                .andExpect(jsonPath("$.data.payload.names.en").value("Hengam"))
                .andExpect(jsonPath("$.data.payload.facts[0].values[0].option").value("FREE_ZONE"));

        update(editor, editId, """
                {"names": {"fa": "جزیرهٔ هنگام"},
                 "facts": [{"attribute": "place_features", "values": [{"option": "PROTECTED_AREA"}]}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("edited"));
        submit(editor, editId).andExpect(status().isOk());

        read("/api/moderation/tasks/" + taskOf(editId), moderator)
                .andExpect(jsonPath("$.data.subjectReview.liveRevisionNumber").value(1))
                .andExpect(jsonPath("$.data.subjectReview.changes[*].field")
                        .value(hasItems("names.fa", "names.en", "aliases.en", "facts.place_features")));
        decide(moderator, editId, Decision.APPROVE, null).andExpect(status().isOk());

        assertThat(subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId()).isEqualTo(editId);
        assertThat(labelLanguagesOf(subjectId)).containsExactly("fa");
        assertThat(subjectAliasRepository.findBySubjectId(subjectId)).isEmpty();
        List<SubjectFact> facts = factsOf(subjectId);
        assertThat(facts).hasSize(1);
        assertThat(attributeOptionRepository.findById(facts.getFirst().getValueOptionId()).orElseThrow().getCode())
                .isEqualTo("PROTECTED_AREA");
        read("/api/subjects/" + subjectId + "/revisions", null)
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // Two edits from the same live revision: once one is approved, the other can be neither
    // approved (which would silently undo the first) nor submitted.
    @Test
    void editBasedOnAReplacedRevisionIsRefusedOnApprovalAndOnSubmit() throws Exception {
        Session author = contributor("srfauth3");
        Session moderator = moderator("srfmod3");
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", islandPayload("لارک"));

        UUID first = startEdit(author, subjectId);
        UUID second = startEdit(author, subjectId);
        UUID third = startEdit(author, subjectId);
        update(author, first, islandPayload("لارک یک")).andExpect(status().isOk());
        update(author, second, islandPayload("لارک دو")).andExpect(status().isOk());
        submit(author, first).andExpect(status().isOk());
        submit(author, second).andExpect(status().isOk());

        decide(moderator, first, Decision.APPROVE, null).andExpect(status().isOk());

        UUID secondTask = taskOf(second);
        send(post("/api/moderation/tasks/" + secondTask + "/claim"), moderator, null).andExpect(status().isOk());
        send(post("/api/moderation/tasks/" + secondTask + "/decide").header(HttpHeaders.ACCEPT_LANGUAGE, "en"),
                moderator, objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SUBJECT_REVISION"))
                .andExpect(jsonPath("$.detail", containsString("based on revision 1")))
                .andExpect(jsonPath("$.detail", containsString("now 2")));

        // Rolled back as a whole: nothing about the second edit or the live state moved.
        assertThat(subjectRevisionRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(SubjectRevisionStatus.PENDING);
        assertThat(moderationTaskRepository.findById(secondTask).orElseThrow().getState())
                .isEqualTo(ModerationTaskState.CLAIMED);
        assertThat(subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId()).isEqualTo(first);
        assertThat(labelRepository.findBySubjectIdIn(List.of(subjectId))).extracting(Label::getValue)
                .containsExactly("لارک یک");

        submit(author, third)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SUBJECT_REVISION"));
    }

    // Every rule the payload breaks is reported at once, each at its own field path, and
    // nothing is stored.
    @Test
    void payloadRulesReportEveryFailureWithItsFieldPath() throws Exception {
        Session author = contributor("srfauth4");
        UUID sourceId = seedSource("Survey");
        UUID islandId = seedSubject(SubjectKind.ISLAND);
        long revisionsBefore = subjectRevisionRepository.count();

        String payload = """
                {"names": {"fa": " ", "en": ""},
                 "aliases": {"fa": ["هرمز", "هرمز"]},
                 "facts": [
                   {"attribute": "no_such_field", "values": [{"text": "x"}]},
                   {"attribute": "scientific_name", "values": [{"text": "Homo"}]},
                   {"attribute": "population", "values": [{"number": 5}]},
                   {"attribute": "area", "values": [{"number": -1, "sourceId": "%1$s"}]},
                   {"attribute": "place_features", "values": [{"option": "NOT_A_CODE"}]},
                   {"attribute": "province", "values": [{"subjectId": "%2$s"}]},
                   {"attribute": "country", "values": [{"number": 3}]},
                   {"attribute": "languages", "values": []}],
                 "geometry": "POLYGON((50 26, 51 26, 51 27, 50 27, 50 26))",
                 "mainImageMediaId": "%3$s"}""".formatted(sourceId, islandId, UUID.randomUUID());

        proposeRaw(author, "ISLAND", payload)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems(
                        "payload.names.fa", "payload.names.en", "payload.aliases.fa[1]",
                        "payload.facts[0].attribute", "payload.facts[1].attribute",
                        "payload.facts[2].values[0].sourceId", "payload.facts[3].values[0].number",
                        "payload.facts[4].values[0].option", "payload.facts[5].values[0].subjectId",
                        "payload.facts[6].values[0]", "payload.facts[7].values", "payload.geometry",
                        "payload.mainImageMediaId")))
                .andExpect(jsonPath("$.errors[?(@.field == 'payload.facts[5].values[0].subjectId')].message")
                        .value(hasItem(containsString("PROVINCE"))));
        assertThat(subjectRevisionRepository.count()).isEqualTo(revisionsBefore);
    }

    // A required field must be present, a single-valued field takes one PREFERRED value (NORMAL
    // ones are fine), and the geometry has to parse.
    @Test
    void requiredSingleValuedAndGeometryRulesHoldForSpecies() throws Exception {
        Session author = contributor("srfauth5");

        proposeRaw(author, "SPECIES", """
                {"names": {"fa": "لاک‌پشت پوزه‌عقابی"},
                 "facts": [{"attribute": "iucn_status", "values": [{"option": "EN"}, {"option": "VU"}]}],
                 "geometry": "not a geometry"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(hasItems("payload.facts", "payload.facts[0].values", "payload.geometry")))
                .andExpect(jsonPath("$.errors[?(@.field == 'payload.facts')].message")
                        .value(hasItem(containsString("scientific_name"))));

        proposeRaw(author, "SPECIES", """
                {"names": {"fa": "لاک‌پشت پوزه‌عقابی"},
                 "facts": [{"attribute": "scientific_name", "values": [{"text": "Eretmochelys imbricata"}]},
                           {"attribute": "iucn_status", "values": [{"option": "CR"}, {"option": "EN", "rank": "NORMAL"}]}],
                 "geometry": "POLYGON((50 26, 51 26, 51 27, 50 27, 50 26))"}""")
                .andExpect(status().isCreated());
    }

    @Test
    void retiredOptionMayBeKeptButNotNewlyChosen() throws Exception {
        Session author = contributor("srfauth6");
        Session moderator = moderator("srfmod6");
        AttributeDefinition field = seedEnumField(uniqueKey("srf_tag"), true, Set.of(SubjectKind.ISLAND), "OLD", "NEW");
        String payloadWithOld = """
                {"names": {"fa": "شیدور"}, "facts": [{"attribute": "%s", "values": [{"option": "OLD"}]}]}"""
                .formatted(field.getKey());
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", payloadWithOld);

        AttributeOption old = attributeOptionRepository.findByAttributeIdIn(List.of(field.getId())).stream()
                .filter(option -> option.getCode().equals("OLD")).findFirst().orElseThrow();
        old.setDeprecated(true);
        attributeOptionRepository.save(old);

        UUID editId = startEdit(author, subjectId);
        update(author, editId, payloadWithOld).andExpect(status().isOk());
        submit(author, editId).andExpect(status().isOk());

        proposeRaw(author, "ISLAND", payloadWithOld.replace("شیدور", "ناز"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payload.facts[0].values[0].option"));
    }

    @Test
    void onlyTheAuthorMayChangeOrSubmitAndOnlyWhileEditable() throws Exception {
        Session author = contributor("srfauth7");
        Session other = contributor("srfother7");
        UUID revisionId = propose(author, "PORT", islandPayload("بندر لنگه"));

        update(other, revisionId, islandPayload("x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_REVISION_AUTHOR"));
        submit(other, revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SUBJECT_REVISION_AUTHOR"));

        submit(author, revisionId).andExpect(status().isOk());
        update(author, revisionId, islandPayload("بندر"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_EDITABLE"));
        submit(author, revisionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_EDITABLE"));

        update(author, UUID.randomUUID(), islandPayload("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));
        send(post("/api/subjects/" + UUID.randomUUID() + "/revisions"), author, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    // Anything not approved is hidden from everyone but its author and moderators -- as a 404,
    // the same as a revision that does not exist.
    @Test
    void unapprovedRevisionsAreVisibleOnlyToTheirAuthorAndModerators() throws Exception {
        Session author = contributor("srfauth8");
        Session other = contributor("srfother8");
        Session moderator = moderator("srfmod8");
        UUID proposalId = propose(author, "ISLAND", islandPayload("فارور"));

        read("/api/subject-revisions/" + proposalId, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_REVISION_NOT_FOUND"));
        read("/api/subject-revisions/" + proposalId, other).andExpect(status().isNotFound());
        read("/api/subject-revisions/" + proposalId, author).andExpect(status().isOk());
        read("/api/subject-revisions/" + proposalId, moderator).andExpect(status().isOk());

        UUID subjectId = approvedSubject(author, moderator, "ISLAND", islandPayload("فارورگان"));
        startEdit(author, subjectId);
        read("/api/subjects/" + subjectId + "/revisions", null).andExpect(jsonPath("$.data.length()").value(1));
        read("/api/subjects/" + subjectId + "/revisions", other).andExpect(jsonPath("$.data.length()").value(1));
        read("/api/subjects/" + subjectId + "/revisions", author).andExpect(jsonPath("$.data.length()").value(2));
        read("/api/subjects/" + subjectId + "/revisions", moderator).andExpect(jsonPath("$.data.length()").value(2));
        read("/api/subjects/" + UUID.randomUUID() + "/revisions", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    // REQUEST_CHANGES sends the revision back and keeps the task for another round; REJECT ends
    // a proposal without creating anything.
    @Test
    void requestChangesReopensTheSameTaskAndRejectCreatesNothing() throws Exception {
        Session author = contributor("srfauth9");
        Session moderator = moderator("srfmod9");
        UUID revisionId = propose(author, "ISLAND", islandPayload("ابوموسی"));
        submit(author, revisionId).andExpect(status().isOk());
        UUID taskId = taskOf(revisionId);

        decide(moderator, revisionId, Decision.REQUEST_CHANGES, "Add the English name.")
                .andExpect(status().isOk());
        assertThat(subjectRevisionRepository.findById(revisionId).orElseThrow().getStatus())
                .isEqualTo(SubjectRevisionStatus.CHANGES_REQUESTED);
        assertThat(moderationTaskRepository.findById(taskId).orElseThrow().getState())
                .isEqualTo(ModerationTaskState.OPEN);

        update(author, revisionId, """
                {"names": {"fa": "ابوموسی", "en": "Abu Musa"}}""").andExpect(status().isOk());
        submit(author, revisionId).andExpect(status().isOk());
        assertThat(taskOf(revisionId)).isEqualTo(taskId);
        decide(moderator, revisionId, Decision.APPROVE, null).andExpect(status().isOk());
        assertThat(subjectOf(revisionId)).isNotNull();

        UUID rejectedId = propose(author, "ISLAND", islandPayload("جزیرهٔ ساختگی"));
        submit(author, rejectedId).andExpect(status().isOk());
        long subjectsBefore = subjectRepository.count();
        decide(moderator, rejectedId, Decision.REJECT, "Not a real island.").andExpect(status().isOk());
        assertThat(subjectRevisionRepository.findById(rejectedId).orElseThrow().getStatus())
                .isEqualTo(SubjectRevisionStatus.REJECTED);
        assertThat(subjectOf(rejectedId)).isNull();
        assertThat(subjectRepository.count()).isEqualTo(subjectsBefore);
    }

    // The phase-2 carry-over: once published subjects hold values for a field, a change that
    // would no longer fit them is refused, while a change they are unaffected by still goes through.
    @Test
    void fieldChangesThatWouldStrandPublishedFactsAreRefused() throws Exception {
        Session author = contributor("srfauth10");
        Session moderator = moderator("srfmod10");
        Session admin = signIn("srfadmin10", Role.ADMIN);
        AttributeDefinition field = seedEnumField(uniqueKey("srf_multi"), true,
                Set.of(SubjectKind.ISLAND, SubjectKind.PORT), "A", "B");
        approvedSubject(author, moderator, "ISLAND", """
                {"names": {"fa": "تنب بزرگ"},
                 "facts": [{"attribute": "%s", "values": [{"option": "A"}, {"option": "B"}]}]}"""
                .formatted(field.getKey()));
        String path = "/api/admin/attribute-definitions/" + field.getId();
        String body = """
                {"valueType": "ENUM", "groupId": "%s", "isMulti": %s, "kinds": %s}""";

        send(patch(path), admin, body.formatted(field.getGroupId(), false, "[\"ISLAND\", \"PORT\"]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_HAS_FACTS"));
        send(patch(path), admin, body.formatted(field.getGroupId(), true, "[\"PORT\"]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_HAS_FACTS"));
        // No published PORT has a value for it, so PORT may go.
        send(patch(path), admin, body.formatted(field.getGroupId(), true, "[\"ISLAND\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kinds").value(hasItem("ISLAND")));

        // A value type change: refused with facts, allowed without.
        AttributeDefinition withFacts = seedEnumField(uniqueKey("srf_bool"), false, Set.of(SubjectKind.ISLAND));
        withFacts.setValueType(AttributeValueType.BOOL);
        attributeDefinitionRepository.save(withFacts);
        approvedSubject(author, moderator, "ISLAND", """
                {"names": {"fa": "تنب کوچک"}, "facts": [{"attribute": "%s", "values": [{"bool": true}]}]}"""
                .formatted(withFacts.getKey()));
        String retype = """
                {"valueType": "DATE", "groupId": "%s", "kinds": ["ISLAND"]}""";
        send(patch("/api/admin/attribute-definitions/" + withFacts.getId()), admin,
                retype.formatted(withFacts.getGroupId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTRIBUTE_HAS_FACTS"));

        AttributeDefinition withoutFacts = seedEnumField(uniqueKey("srf_free"), false, Set.of(SubjectKind.ISLAND));
        withoutFacts.setValueType(AttributeValueType.BOOL);
        attributeDefinitionRepository.save(withoutFacts);
        send(patch("/api/admin/attribute-definitions/" + withoutFacts.getId()), admin,
                retype.formatted(withoutFacts.getGroupId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valueType").value("DATE"));
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // ModerationService gained a third target. An article revision task still carries neither
    // review, reports no subject revision, and is approved exactly as before.
    @Test
    void articleRevisionTasksAreDecidedAsBefore() throws Exception {
        Session author = contributor("srfauth11");
        Session moderator = moderator("srfmod11");
        String slug = "srf-article-" + UUID.randomUUID();
        String created = send(post("/api/articles"), author, """
                {"canonicalLanguage": "fa", "slug": "%s", "title": "Hormuz",
                 "body": {"type": "doc", "content": []}}""".formatted(slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        String revisions = read("/api/articles/" + articleId + "/translations/fa/revisions", author)
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        send(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit"), author, null)
                .andExpect(status().isOk());
        UUID taskId = moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId();

        read("/api/moderation/tasks/" + taskId, moderator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.revisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.data.task.subjectRevisionId").doesNotExist())
                .andExpect(jsonPath("$.data.subjectReview").doesNotExist())
                .andExpect(jsonPath("$.data.mediaReview").doesNotExist());
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null)))
                .andExpect(status().isOk());
        read("/api/articles/" + articleId + "/translations/fa", null).andExpect(status().isOk());
    }

    // Only reads of subject revisions were made public; every write still needs an account.
    @Test
    void subjectRevisionWritesStillNeedAnAccount() throws Exception {
        mockMvc.perform(post("/api/subject-revisions")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"ISLAND\", \"payload\": {\"names\": {\"fa\": \"x\"}}}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/subject-revisions/" + UUID.randomUUID())
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\": {\"names\": {\"fa\": \"x\"}}}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/subjects/" + UUID.randomUUID() + "/revisions").with(xsrf()))
                .andExpect(status().isUnauthorized());
    }

    // The request envelope is still checked by bean validation, with the same per-field shape as
    // the payload rules.
    @Test
    void missingKindAndPayloadStillProduceThePerFieldBreakdown() throws Exception {
        Session author = contributor("srfauth12");
        send(post("/api/subject-revisions"), author, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("kind", "payload")));
    }
}

package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import tools.jackson.databind.ObjectMapper;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 7: the duplicate lookup, soft delete and restore, merge, and the admin's permanent delete.
// Subjects, articles and edits go through the real proposal, article and moderation routes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectLifecycleFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String PLAIN_BODY = """
            {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "متن"}]}]}""";

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
    private SubjectRedirectRepository subjectRedirectRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // --- Helpers ---

    private record Session(UUID userId, Cookie access, Cookie csrf) {
    }

    private record DraftArticle(UUID id, String slug) {
    }

    private record PublishedArticle(UUID id, UUID revisionId, String slug) {
    }

    private Session signIn(String name, Role... roles) throws Exception {
        return signIn(name, null, roles);
    }

    private Session signIn(String name, @Nullable SubjectKind expertise, Role... roles) throws Exception {
        String email = name + "@slf.example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(name, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        for (Role role : roles) {
            userRoleRepository.save(UserRole.builder().user(user).role(role)
                    .expertise(role == Role.EXPERT_REVIEWER ? expertise : null).build());
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

    private ResultActions send(MockHttpServletRequestBuilder request, Session session, @Nullable String json)
            throws Exception {
        request.cookie(session.access(), session.csrf())
                .header("X-XSRF-TOKEN", maskCsrfToken(session.csrf().getValue()));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions decide(Session moderator, UUID taskId, Decision decision) throws Exception {
        send(post("/api/moderation/tasks/" + taskId + "/claim"), moderator, null).andExpect(status().isOk());
        return send(post("/api/moderation/tasks/" + taskId + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(decision,
                        decision == Decision.APPROVE ? null : "duplicate")));
    }

    private UUID subjectTask(UUID revisionId) {
        return moderationTaskRepository.findBySubjectRevisionId(revisionId).orElseThrow().getId();
    }

    private UUID approvedSubject(Session author, Session moderator, String kind, String payload) throws Exception {
        String body = send(post("/api/subject-revisions"), author, """
                {"kind": "%s", "payload": %s}""".formatted(kind, payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        submit(author, revisionId).andExpect(status().isOk());
        decide(moderator, subjectTask(revisionId), Decision.APPROVE).andExpect(status().isOk());
        return subjectRevisionRepository.findById(revisionId).orElseThrow().getSubjectId();
    }

    private UUID startEdit(Session author, UUID subjectId) throws Exception {
        String body = send(post("/api/subjects/" + subjectId + "/revisions"), author, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private ResultActions submit(Session author, UUID revisionId) throws Exception {
        return send(post("/api/subject-revisions/" + revisionId + "/submit"), author, null);
    }

    private ResultActions softDelete(Session session, UUID subjectId) throws Exception {
        return send(delete("/api/subjects/" + subjectId), session, null);
    }

    private ResultActions restore(Session session, UUID subjectId) throws Exception {
        return send(post("/api/subjects/" + subjectId + "/restorations"), session, null);
    }

    private ResultActions merge(Session session, UUID sourceId, @Nullable UUID targetId) throws Exception {
        String body = targetId == null ? "{}" : "{\"targetId\": \"%s\"}".formatted(targetId);
        return send(post("/api/subjects/" + sourceId + "/merges"), session, body);
    }

    private ResultActions hardDelete(Session session, UUID subjectId) throws Exception {
        return send(delete("/api/admin/subjects/" + subjectId), session, null);
    }

    private ResultActions lookup(String query, @Nullable String kind) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/subjects/lookup").param("q", query);
        if (kind != null) {
            request.param("kind", kind);
        }
        return mockMvc.perform(request);
    }

    private DraftArticle draftArticle(Session author, @Nullable UUID subjectId, String body) throws Exception {
        String subject = subjectId == null ? "null" : "\"" + subjectId + "\"";
        String slug = "slf-" + UUID.randomUUID();
        String created = send(post("/api/articles"), author, """
                {"subjectId": %s, "canonicalLanguage": "fa", "slug": "%s", "title": "عنوان", "body": %s}"""
                .formatted(subject, slug, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new DraftArticle(UUID.fromString(JsonPath.read(created, "$.data.id")), slug);
    }

    private PublishedArticle publishedArticle(Session author, Session moderator, @Nullable UUID subjectId,
            String body) throws Exception {
        DraftArticle article = draftArticle(author, subjectId, body);
        String revisions = mockMvc.perform(get("/api/articles/" + article.id() + "/translations/fa/revisions")
                        .cookie(author.access()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        send(post("/api/articles/" + article.id() + "/translations/fa/revisions/" + revisionId + "/submit"), author,
                null)
                .andExpect(status().isOk());
        decide(moderator, moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId(),
                Decision.APPROVE).andExpect(status().isOk());
        return new PublishedArticle(article.id(), revisionId, article.slug());
    }

    private ResultActions infobox(UUID subjectId, String language) throws Exception {
        return mockMvc.perform(get("/api/subjects/" + subjectId + "/infobox")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language));
    }

    private List<String> infoboxValues(UUID subjectId, String attribute, String valuePath) throws Exception {
        String body = infobox(subjectId, "fa").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.groups[*].rows[?(@.attribute == '%s')].values[*].%s"
                .formatted(attribute, valuePath));
    }

    private String livePayloadJson(UUID subjectId) throws Exception {
        UUID revisionId = subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId();
        return mockMvc.perform(get("/api/subject-revisions/" + revisionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long auditRows(String action, UUID subjectId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> action.equals(row.getAction()))
                .map(AuditLog::getDetail)
                .filter(detail -> detail.contains(subjectId.toString()))
                .count();
    }

    // A distinct marker per test, so lookups and listings only see that test's subjects in a database
    // the whole suite shares.
    private static String token() {
        return "z" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String payload(String... parts) {
        return "{" + String.join(", ", parts) + "}";
    }

    private static String names(String fa, @Nullable String en) {
        return en == null ? "\"names\": {\"fa\": \"%s\"}".formatted(fa)
                : "\"names\": {\"fa\": \"%s\", \"en\": \"%s\"}".formatted(fa, en);
    }

    private static String aliases(List<String> fa, List<String> en) {
        return "\"aliases\": {\"fa\": %s, \"en\": %s}".formatted(jsonStrings(fa), jsonStrings(en));
    }

    private static String facts(String... facts) {
        return "\"facts\": [" + String.join(", ", facts) + "]";
    }

    private static String references(String attribute, UUID... subjectIds) {
        return "{\"attribute\": \"%s\", \"values\": [%s]}".formatted(attribute, Stream.of(subjectIds)
                .map(id -> "{\"subjectId\": \"%s\"}".formatted(id))
                .collect(Collectors.joining(", ")));
    }

    private static String options(String attribute, String... codes) {
        return "{\"attribute\": \"%s\", \"values\": [%s]}".formatted(attribute, Stream.of(codes)
                .map(code -> "{\"option\": \"%s\"}".formatted(code))
                .collect(Collectors.joining(", ")));
    }

    private static String text(String attribute, String value) {
        return "{\"attribute\": \"%s\", \"values\": [{\"text\": \"%s\"}]}".formatted(attribute, value);
    }

    private static String jsonStrings(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private static String linkBody(UUID subjectId) {
        return """
                {"type": "doc", "content": [{"type": "paragraph",
                 "content": [{"type": "text", "text": "ببینید "}, {"type": "subjectLink", "subjectId": "%s"}]}]}"""
                .formatted(subjectId);
    }

    // --- Smoke: the duplicate lookup ---

    // Names in any language, aliases and scientific names all match, whatever the case, accents,
    // spacing and Arabic-or-Persian letter forms; each subject comes back once with what it matched.
    @Test
    void lookupFindsSubjectsByNameAliasOrScientificNameAcrossLetterForms() throws Exception {
        Session author = signIn("slfauth1");
        Session moderator = signIn("slfmod1", Role.MODERATOR);
        String token = token();
        UUID islandId = approvedSubject(author, moderator, "ISLAND", payload(
                names("جزیرهٔ قشم " + token, "Qeshm Island " + token), aliases(List.of(), List.of("Gheshm" + token))));
        UUID speciesId = approvedSubject(author, moderator, "SPECIES", payload(
                names("لاک‌پشت سبز " + token, null), facts(text("scientific_name", "Chelonia mydas " + token))));

        // Arabic yeh and teh marbuta, no hamza, for a Persian name written with ی, ه and ٔ.
        lookup("جزيرة قشم " + token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(contains(islandId.toString())))
                .andExpect(jsonPath("$.data[0].kind").value("ISLAND"))
                .andExpect(jsonPath("$.data[0].matchedOn").value("NAME"))
                .andExpect(jsonPath("$.data[0].matchedLanguage").value("fa"))
                .andExpect(jsonPath("$.data[0].matchedValue").value("جزیرهٔ قشم " + token))
                .andExpect(jsonPath("$.data[0].name.value").value("جزیرهٔ قشم " + token));
        lookup("QÉSHM island " + token.toUpperCase(), null)
                .andExpect(jsonPath("$.data[*].id").value(contains(islandId.toString())))
                .andExpect(jsonPath("$.data[0].matchedLanguage").value("en"));
        lookup("gheshm" + token, null)
                .andExpect(jsonPath("$.data[*].id").value(contains(islandId.toString())))
                .andExpect(jsonPath("$.data[0].matchedOn").value("ALIAS"));
        lookup("chelonia  mydas " + token, null)
                .andExpect(jsonPath("$.data[*].id").value(contains(speciesId.toString())))
                .andExpect(jsonPath("$.data[0].matchedOn").value("SCIENTIFIC_NAME"))
                .andExpect(jsonPath("$.data[0].matchedLanguage").doesNotExist());

        lookup(token, null).andExpect(jsonPath("$.data[*].id")
                .value(containsInAnyOrder(islandId.toString(), speciesId.toString())));
        lookup(token, "species").andExpect(jsonPath("$.data[*].id").value(contains(speciesId.toString())));
        lookup(token, "PORT").andExpect(jsonPath("$.data.length()").value(0));
    }

    // Exact matches first, then names starting with the term, then the rest -- not newest first.
    @Test
    void lookupRanksExactThenPrefixThenSubstringMatches() throws Exception {
        Session author = signIn("slfauth2");
        Session moderator = signIn("slfmod2", Role.MODERATOR);
        String token = token();
        UUID exact = approvedSubject(author, moderator, "ISLAND", payload(names(token, null)));
        UUID prefix = approvedSubject(author, moderator, "ISLAND", payload(names(token + " بزرگ", null)));
        UUID substring = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token, null)));

        lookup(token, "ISLAND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id")
                        .value(contains(exact.toString(), prefix.toString(), substring.toString())));
        mockMvc.perform(get("/api/subjects/lookup").param("q", token).param("size", "1"))
                .andExpect(jsonPath("$.data[*].id").value(contains(exact.toString())));
    }

    @Test
    void lookupRejectsAMissingShortOrUnknownFilter() throws Exception {
        mockMvc.perform(get("/api/subjects/lookup")).andExpect(status().isBadRequest());
        lookup("ق", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        lookup("قشم", "BOAT")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
        mockMvc.perform(get("/api/subjects/lookup").param("q", "قشم").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        // Punctuation alone folds to nothing, which matches nothing rather than everything.
        lookup("---", null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- Smoke: soft delete and restore ---

    // A deleted subject leaves every read -- its own, listings, facets, filters, lookups and other
    // subjects' infoboxes -- and a restore brings all of it back. An edit that keeps a reference to it
    // still goes through; a new one pointing at it does not.
    @Test
    void deletedSubjectIsHiddenEverywhereUntilRestored() throws Exception {
        Session author = signIn("slfauth3");
        Session moderator = signIn("slfmod3", Role.MODERATOR);
        String token = token();
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload(names("استان " + token, null)));
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیره " + token, null), facts(references("province", provinceId))));
        assertThat(infoboxValues(islandId, "province", "subject.id")).containsExactly(provinceId.toString());

        softDelete(moderator, provinceId).andExpect(status().isNoContent());

        assertThat(subjectRepository.findById(provinceId).orElseThrow().isDeleted()).isTrue();
        assertThat(auditRows("SUBJECT_SOFT_DELETE", provinceId)).isEqualTo(1);
        mockMvc.perform(get("/api/subjects/" + provinceId)).andExpect(status().isNotFound());
        infobox(provinceId, "fa").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/subjects/" + provinceId + "/articles")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/subjects/" + provinceId + "/referenced-by").param("attribute", "province"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/subjects").param("kind", "PROVINCE").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].id").value(not(hasItem(provinceId.toString()))));
        mockMvc.perform(get("/api/subjects").param("kind", "ISLAND").param("size", "1"))
                .andExpect(jsonPath("$.data.facets[?(@.key == 'province')].options[*].code")
                        .value(not(hasItem(provinceId.toString()))));
        mockMvc.perform(get("/api/subjects").param("kind", "ISLAND").param("attr.province", provinceId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("attr.province"));
        lookup(token, "PROVINCE").andExpect(jsonPath("$.data.length()").value(0));
        assertThat(infoboxValues(islandId, "province", "subject.id")).isEmpty();

        // Kept from the live revision: fine. Newly chosen: as if it did not exist.
        UUID editId = startEdit(author, islandId);
        submit(author, editId).andExpect(status().isOk());
        decide(moderator, subjectTask(editId), Decision.APPROVE).andExpect(status().isOk());
        send(post("/api/subject-revisions"), author, """
                {"kind": "ISLAND", "payload": %s}""".formatted(
                        payload(names("جزیرهٔ دیگر " + token, null), facts(references("province", provinceId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payload.facts[0].values[0].subjectId"));

        restore(moderator, provinceId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(provinceId.toString()))
                .andExpect(jsonPath("$.data.redirectedFrom").doesNotExist());

        assertThat(auditRows("SUBJECT_RESTORE", provinceId)).isEqualTo(1);
        mockMvc.perform(get("/api/subjects/" + provinceId)).andExpect(status().isOk());
        lookup(token, "PROVINCE").andExpect(jsonPath("$.data[*].id").value(contains(provinceId.toString())));
        assertThat(infoboxValues(islandId, "province", "subject.id")).containsExactly(provinceId.toString());
    }

    @Test
    void onlyModeratorsMayDeleteOrRestore() throws Exception {
        Session author = signIn("slfauth4");
        Session moderator = signIn("slfmod4", Role.MODERATOR);
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));

        mockMvc.perform(delete("/api/subjects/" + subjectId).with(xsrf())).andExpect(status().isUnauthorized());
        softDelete(author, subjectId).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/subjects/" + subjectId).cookie(moderator.access()))
                .andExpect(status().isForbidden());
        assertThat(subjectRepository.findById(subjectId).orElseThrow().isDeleted()).isFalse();

        softDelete(moderator, subjectId).andExpect(status().isNoContent());
        restore(author, subjectId).andExpect(status().isForbidden());
        assertThat(subjectRepository.findById(subjectId).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void deleteAndRestoreRefuseSubjectsInTheWrongState() throws Exception {
        Session author = signIn("slfauth5");
        Session moderator = signIn("slfmod5", Role.MODERATOR);
        UUID withDraftArticle = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        draftArticle(author, withDraftArticle, PLAIN_BODY);
        UUID plain = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));

        // Even an unpublished article blocks it: articles never change subject.
        softDelete(moderator, withDraftArticle)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_HAS_ARTICLES"));
        restore(moderator, plain)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_DELETED"));
        softDelete(moderator, plain).andExpect(status().isNoContent());
        softDelete(moderator, plain)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        softDelete(moderator, UUID.randomUUID()).andExpect(status().isNotFound());
        restore(moderator, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // No edit, approval, verification, main-article switch or new article lands on a deleted
    // subject; pending work on it can still be rejected off the queue.
    @Test
    void aDeletedSubjectTakesNoEditsArticlesOrVerifications() throws Exception {
        Session author = signIn("slfauth6");
        Session moderator = signIn("slfmod6", Role.MODERATOR);
        Session expert = signIn("slfexp6", SubjectKind.ISLAND, Role.EXPERT_REVIEWER);
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        UUID liveRevisionId = subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId();
        UUID draftId = startEdit(author, subjectId);
        UUID pendingId = startEdit(author, subjectId);
        submit(author, pendingId).andExpect(status().isOk());
        UUID pendingTask = subjectTask(pendingId);

        softDelete(moderator, subjectId).andExpect(status().isNoContent());

        send(post("/api/subjects/" + subjectId + "/revisions"), author, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        send(patch("/api/subject-revisions/" + draftId), author, """
                {"payload": %s}""".formatted(payload(names("جزیرهٔ تازه", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        submit(author, draftId).andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        decide(moderator, pendingTask, Decision.APPROVE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        send(post("/api/moderation/tasks/" + pendingTask + "/decide"), moderator,
                objectMapper.writeValueAsString(new DecideRequest(Decision.REJECT, "subject removed")))
                .andExpect(status().isOk());

        send(post("/api/articles"), author, """
                {"subjectId": "%s", "canonicalLanguage": "fa", "slug": "slf-%s", "title": "عنوان", "body": %s}"""
                .formatted(subjectId, UUID.randomUUID(), PLAIN_BODY))
                .andExpect(status().isNotFound());
        send(patch("/api/subjects/" + subjectId + "/main-article"), moderator,
                "{\"articleId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        send(post("/api/subject-revisions/" + liveRevisionId + "/verifications"), expert, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        mockMvc.perform(get("/api/expert-reviewer/verification-queue").param("size", "100")
                        .cookie(expert.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].subjectId").value(not(hasItem(subjectId.toString()))));
    }

    // --- Smoke: merge ---

    // Everything the source had ends up on the target, through a new published revision, and the
    // source's id, aliases and links lead there.
    @Test
    void mergeFoldsTheSourceIntoTheTargetAndRedirectsIt() throws Exception {
        Session author = signIn("slfauth7");
        Session moderator = signIn("slfmod7", Role.MODERATOR);
        String token = token();
        UUID firstProvince = approvedSubject(author, moderator, "PROVINCE", payload(names("استان یک " + token, null)));
        UUID secondProvince = approvedSubject(author, moderator, "PROVINCE", payload(names("استان دو " + token, null)));
        UUID firstStrait = approvedSubject(author, moderator, "STRAIT", payload(names("تنگهٔ یک " + token, null)));
        UUID secondStrait = approvedSubject(author, moderator, "STRAIT", payload(names("تنگهٔ دو " + token, null)));
        UUID targetId = approvedSubject(author, moderator, "ISLAND", payload(
                names("جزیرهٔ مقصد " + token, null),
                aliases(List.of("نام کهن " + token), List.of()),
                facts(references("province", firstProvince), references("located_in", firstStrait),
                        options("languages", "FA"))));
        UUID sourceId = approvedSubject(author, moderator, "ISLAND", payload(
                names("جزیرهٔ مبدأ " + token, "Source Island " + token),
                aliases(List.of(), List.of("Old Source " + token)),
                facts(references("province", secondProvince), references("located_in", firstStrait, secondStrait),
                        options("languages", "FA", "AR"), options("place_features", "FREE_ZONE"))));
        PublishedArticle sourcePage = publishedArticle(author, moderator, sourceId, PLAIN_BODY);
        PublishedArticle linking = publishedArticle(author, moderator, null, linkBody(sourceId));
        UUID targetEdit = startEdit(author, targetId);

        String merged = merge(moderator, sourceId, targetId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$.data.targetId").value(targetId.toString()))
                // r2 is the draft edit started above.
                .andExpect(jsonPath("$.data.revisionNumber").value(3))
                .andExpect(jsonPath("$.data.mergedAt").exists())
                .andReturn().getResponse().getContentAsString();
        UUID mergeRevisionId = UUID.fromString(JsonPath.read(merged, "$.data.revisionId"));

        // The source's id now answers with the target; everything else about it is gone.
        mockMvc.perform(get("/api/subjects/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(targetId.toString()))
                .andExpect(jsonPath("$.data.redirectedFrom").value(sourceId.toString()));
        infobox(sourceId, "fa").andExpect(status().isNotFound());
        assertThat(subjectRedirectRepository.findById(sourceId).orElseThrow().getTargetId()).isEqualTo(targetId);
        assertThat(auditRows("SUBJECT_MERGE", sourceId)).isEqualTo(1);

        // Names: the target's, the missing English one taken from the source.
        infobox(targetId, "en")
                .andExpect(jsonPath("$.data.name.value").value("Source Island " + token))
                .andExpect(jsonPath("$.data.name.isFallback").value(false))
                .andExpect(jsonPath("$.data.revision.number").value(3));
        // A single-valued field keeps the target's value on show; multi-valued ones take both sides.
        assertThat(infoboxValues(targetId, "province", "subject.id")).containsExactly(firstProvince.toString());
        assertThat(infoboxValues(targetId, "located_in", "subject.id"))
                .containsExactly(firstStrait.toString(), secondStrait.toString());
        assertThat(infoboxValues(targetId, "languages", "option.code")).containsExactly("FA", "AR");
        assertThat(infoboxValues(targetId, "place_features", "option.code")).containsExactly("FREE_ZONE");

        String revision = livePayloadJson(targetId);
        assertThat(JsonPath.<String>read(revision, "$.data.id")).isEqualTo(mergeRevisionId.toString());
        assertThat(JsonPath.<String>read(revision, "$.data.authorId")).isEqualTo(moderator.userId().toString());
        assertThat(JsonPath.<String>read(revision, "$.data.status")).isEqualTo("APPROVED");
        assertThat(JsonPath.<List<String>>read(revision,
                "$.data.payload.facts[?(@.attribute == 'province')].values[*].rank"))
                .containsExactly("PREFERRED", "NORMAL");
        assertThat(JsonPath.<List<String>>read(revision, "$.data.payload.aliases.fa"))
                .containsExactly("نام کهن " + token, "جزیرهٔ مبدأ " + token);
        assertThat(JsonPath.<List<String>>read(revision, "$.data.payload.aliases.en"))
                .containsExactly("Old Source " + token);

        // The source's old name finds the target.
        lookup("جزیرهٔ مبدأ " + token, null)
                .andExpect(jsonPath("$.data[*].id").value(contains(targetId.toString())))
                .andExpect(jsonPath("$.data[0].matchedOn").value("ALIAS"));

        // Its article moved, and stays main since the target had none; links to it show the target.
        assertThat(articleRepository.findById(sourcePage.id()).orElseThrow().getSubjectId()).isEqualTo(targetId);
        mockMvc.perform(get("/api/subjects/" + targetId + "/articles"))
                .andExpect(jsonPath("$.data[*].articleId").value(contains(sourcePage.id().toString())))
                .andExpect(jsonPath("$.data[0].isMain").value(true));
        mockMvc.perform(get("/api/articles/" + linking.id() + "/translations/fa/revisions/" + linking.revisionId()))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].name.value".formatted(sourceId))
                        .value("جزیرهٔ مقصد " + token))
                .andExpect(jsonPath("$.data.subjectLinks['%s'].slug".formatted(sourceId)).value(sourcePage.slug()));

        // A new article naming the source is bound to the target.
        UUID lateArticle = draftArticle(author, sourceId, PLAIN_BODY).id();
        assertThat(articleRepository.findById(lateArticle).orElseThrow().getSubjectId()).isEqualTo(targetId);

        // A merge is final, and an edit of the target from before it has to be redone.
        restore(moderator, sourceId).andExpect(jsonPath("$.code").value("SUBJECT_MERGED"));
        merge(moderator, sourceId, targetId).andExpect(jsonPath("$.code").value("SUBJECT_DELETED"));
        submit(author, targetEdit)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SUBJECT_REVISION"));
    }

    // Other subjects' references follow the merge, including in revisions written before it; the
    // target keeps its own main article.
    @Test
    void mergeRepointsOtherSubjectsAndKeepsTheTargetsMainArticle() throws Exception {
        Session author = signIn("slfauth8");
        Session moderator = signIn("slfmod8", Role.MODERATOR);
        String token = token();
        UUID targetId = approvedSubject(author, moderator, "STRAIT",
                payload(names("تنگهٔ مقصد " + token, null), facts(options("country", "IR"))));
        UUID sourceId = approvedSubject(author, moderator, "STRAIT",
                payload(names("تنگهٔ مبدأ " + token, null), facts(options("country", "IR", "OM"))));
        UUID onlySource = approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیرهٔ یک " + token, null), facts(references("located_in", sourceId))));
        UUID both = approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیرهٔ دو " + token, null), facts(references("located_in", sourceId, targetId))));
        // Proposed and submitted before the merge, approved after it.
        String proposal = send(post("/api/subject-revisions"), author, """
                {"kind": "ISLAND", "payload": %s}""".formatted(
                        payload(names("جزیرهٔ سه " + token, null), facts(references("located_in", sourceId)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID proposalId = UUID.fromString(JsonPath.read(proposal, "$.data.id"));
        submit(author, proposalId).andExpect(status().isOk());
        PublishedArticle targetPage = publishedArticle(author, moderator, targetId, PLAIN_BODY);
        PublishedArticle sourcePage = publishedArticle(author, moderator, sourceId, PLAIN_BODY);

        merge(moderator, sourceId, targetId).andExpect(status().isCreated());
        decide(moderator, subjectTask(proposalId), Decision.APPROVE).andExpect(status().isOk());
        UUID late = subjectRevisionRepository.findById(proposalId).orElseThrow().getSubjectId();

        assertThat(infoboxValues(targetId, "country", "option.code")).containsExactly("IR", "OM");
        assertThat(infoboxValues(onlySource, "located_in", "subject.id")).containsExactly(targetId.toString());
        assertThat(infoboxValues(both, "located_in", "subject.id")).containsExactly(targetId.toString());
        assertThat(infoboxValues(late, "located_in", "subject.id")).containsExactly(targetId.toString());
        assertThat(JsonPath.<List<String>>read(livePayloadJson(late), "$.data.payload.facts[0].values[*].subjectId"))
                .containsExactly(targetId.toString());
        mockMvc.perform(get("/api/subjects/" + targetId + "/referenced-by").param("attribute", "located_in"))
                .andExpect(jsonPath("$.data.items[*].id")
                        .value(containsInAnyOrder(onlySource.toString(), both.toString(), late.toString())));
        mockMvc.perform(get("/api/subjects/" + targetId + "/articles"))
                .andExpect(jsonPath("$.data[*].articleId")
                        .value(contains(targetPage.id().toString(), sourcePage.id().toString())))
                .andExpect(jsonPath("$.data[*].isMain").value(contains(true, false)));
    }

    // Redirects stay one hop: merging the target away moves the redirects that pointed at it, and the
    // references that followed the first merge follow the second.
    @Test
    void mergingATargetAwayRepointsItsRedirects() throws Exception {
        Session author = signIn("slfauth9");
        Session moderator = signIn("slfmod9", Role.MODERATOR);
        String token = token();
        UUID first = approvedSubject(author, moderator, "PROVINCE", payload(names("استان الف " + token, null)));
        UUID second = approvedSubject(author, moderator, "PROVINCE", payload(names("استان ب " + token, null)));
        UUID third = approvedSubject(author, moderator, "PROVINCE", payload(names("استان پ " + token, null)));
        UUID islandId = approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیره " + token, null), facts(references("province", first))));

        merge(moderator, first, second).andExpect(status().isCreated());
        merge(moderator, second, third).andExpect(status().isCreated());

        mockMvc.perform(get("/api/subjects/" + first))
                .andExpect(jsonPath("$.data.id").value(third.toString()))
                .andExpect(jsonPath("$.data.redirectedFrom").value(first.toString()));
        assertThat(subjectRedirectRepository.findById(first).orElseThrow().getTargetId()).isEqualTo(third);
        lookup("استان الف " + token, null).andExpect(jsonPath("$.data[*].id").value(contains(third.toString())));
        assertThat(infoboxValues(islandId, "province", "subject.id")).containsExactly(third.toString());
        // A filter naming a merged-away subject selects where it ended up.
        mockMvc.perform(get("/api/subjects").param("kind", "ISLAND").param("attr.province", first.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(contains(islandId.toString())));
    }

    @Test
    void mergeRefusesBadTargetsOtherKindsAndNonModerators() throws Exception {
        Session author = signIn("slfauth10");
        Session moderator = signIn("slfmod10", Role.MODERATOR);
        UUID source = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        UUID port = approvedSubject(author, moderator, "PORT", payload(names("بندر " + token(), null)));
        UUID deletedIsland = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        softDelete(moderator, deletedIsland).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/subjects/" + source + "/merges").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetId\": \"%s\"}".formatted(port)))
                .andExpect(status().isUnauthorized());
        merge(author, source, port).andExpect(status().isForbidden());
        merge(moderator, source, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetId"));
        for (UUID target : List.of(source, UUID.randomUUID(), deletedIsland)) {
            merge(moderator, source, target)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("targetId"));
        }
        merge(moderator, UUID.randomUUID(), source).andExpect(status().isNotFound());
        merge(moderator, source, port)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_KIND_MISMATCH"));
        assertThat(subjectRepository.findById(source).orElseThrow().isDeleted()).isFalse();
    }

    // Too many aliases once combined: the merge reports the field to fix and leaves both untouched.
    @Test
    void aMergeThatBreaksTheFieldRulesChangesNothing() throws Exception {
        Session author = signIn("slfauth11");
        Session moderator = signIn("slfmod11", Role.MODERATOR);
        String token = token();
        List<String> twentyAliases = IntStream.rangeClosed(1, 20).mapToObj(i -> "نام " + i + " " + token).toList();
        UUID targetId = approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیرهٔ مقصد " + token, null), aliases(twentyAliases, List.of())));
        UUID sourceId = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ مبدأ " + token, null)));
        PublishedArticle sourcePage = publishedArticle(author, moderator, sourceId, PLAIN_BODY);

        merge(moderator, sourceId, targetId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_MERGE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("payload.aliases.fa"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        mockMvc.perform(get("/api/subjects/" + sourceId))
                .andExpect(jsonPath("$.data.id").value(sourceId.toString()))
                .andExpect(jsonPath("$.data.redirectedFrom").doesNotExist());
        assertThat(subjectRedirectRepository.existsById(sourceId)).isFalse();
        assertThat(articleRepository.findById(sourcePage.id()).orElseThrow().getSubjectId()).isEqualTo(sourceId);
        assertThat(subjectRevisionRepository.findMaxRevisionNumber(targetId)).isEqualTo(1);
    }

    // --- Smoke: permanent delete ---

    @Test
    void adminDeletesAnUnreferencedSubjectForGoodEvenIfProseLinksToIt() throws Exception {
        Session author = signIn("slfauth12");
        Session moderator = signIn("slfmod12", Role.MODERATOR);
        Session admin = signIn("slfadm12", Role.ADMIN);
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        PublishedArticle linking = publishedArticle(author, moderator, null, linkBody(subjectId));
        UUID revisionId = subjectLiveRevisionRepository.findById(subjectId).orElseThrow().getRevisionId();

        hardDelete(moderator, subjectId).andExpect(status().isForbidden());
        hardDelete(admin, subjectId).andExpect(status().isNoContent());

        assertThat(subjectRepository.findById(subjectId)).isEmpty();
        assertThat(subjectRevisionRepository.findById(revisionId)).isEmpty();
        assertThat(auditRows("SUBJECT_HARD_DELETE", subjectId)).isEqualTo(1);
        mockMvc.perform(get("/api/subjects/" + subjectId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + linking.id() + "/translations/fa/revisions/" + linking.revisionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectLinks.length()").value(0));
        hardDelete(admin, subjectId).andExpect(status().isNotFound());
    }

    @Test
    void permanentDeleteRefusesSubjectsThatArticlesOrFactsStillPointAt() throws Exception {
        Session author = signIn("slfauth13");
        Session moderator = signIn("slfmod13", Role.MODERATOR);
        Session admin = signIn("slfadm13", Role.ADMIN);
        UUID provinceId = approvedSubject(author, moderator, "PROVINCE", payload(names("استان " + token(), null)));
        approvedSubject(author, moderator, "ISLAND",
                payload(names("جزیرهٔ " + token(), null), facts(references("province", provinceId))));
        UUID withArticle = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        draftArticle(author, withArticle, PLAIN_BODY);
        UUID deleted = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token(), null)));
        softDelete(moderator, deleted).andExpect(status().isNoContent());

        hardDelete(admin, provinceId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_REFERENCED"));
        hardDelete(admin, withArticle)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_HAS_ARTICLES"));
        assertThat(subjectRepository.findById(provinceId)).isPresent();
        assertThat(subjectRepository.findById(withArticle)).isPresent();
        // A soft-deleted subject can go for good too.
        hardDelete(admin, deleted).andExpect(status().isNoContent());
        assertThat(subjectRepository.findById(deleted)).isEmpty();
    }

    // --- Regression: undeleted subjects read as before ---

    @Test
    void anUndeletedSubjectReadsAsBeforeWithNoRedirect() throws Exception {
        Session author = signIn("slfauth14");
        Session moderator = signIn("slfmod14", Role.MODERATOR);
        String token = token();
        UUID subjectId = approvedSubject(author, moderator, "ISLAND", payload(names("جزیرهٔ " + token, null)));

        mockMvc.perform(get("/api/subjects/" + subjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(subjectId.toString()))
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.redirectedFrom").doesNotExist());
        infobox(subjectId, "fa").andExpect(status().isOk());
        mockMvc.perform(get("/api/subjects").param("kind", "ISLAND").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(subjectId.toString())));
        lookup(token, null).andExpect(jsonPath("$.data[*].id").value(contains(subjectId.toString())));
    }
}

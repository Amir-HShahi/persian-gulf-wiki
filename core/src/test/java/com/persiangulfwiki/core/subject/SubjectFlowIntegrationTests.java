package com.persiangulfwiki.core.subject;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.subject.entity.FishingGround;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.Province;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.FishingGroundRepository;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.ProvinceRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The subject read path. Subjects are created only by approving a proposal since phase 3 --
// SubjectRevisionFlowIntegrationTests drives that end to end, geometry round trip included -- so
// the subjects read here are seeded straight through the repositories.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

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
    private IslandRepository islandRepository;

    @Autowired
    private ProvinceRepository provinceRepository;

    @Autowired
    private FishingGroundRepository fishingGroundRepository;

    private User seedModerator(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
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

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    private Subject seedSubject(SubjectKind kind) {
        return subjectRepository.save(Subject.builder().kind(kind).build());
    }

    private static Polygon square(double lon, double lat) {
        LinearRing ring = WGS84.createLinearRing(new Coordinate[] {
                new Coordinate(lon, lat), new Coordinate(lon + 1, lat), new Coordinate(lon + 1, lat + 1),
                new Coordinate(lon, lat + 1), new Coordinate(lon, lat)});
        return WGS84.createPolygon(ring);
    }

    // The point survives a round trip through PostGIS and is readable by a caller with no account.
    // areaKm2 is gone from the response (V25): an island's area is the `area` fact now.
    @Test
    void anonymousReadsIslandWithItsPointAndNoAreaKm2() throws Exception {
        Subject island = seedSubject(SubjectKind.ISLAND);
        islandRepository.save(Island.builder().subject(island)
                .location(WGS84.createPoint(new Coordinate(52.1, 26.4))).build());

        mockMvc.perform(get("/api/subjects/" + island.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.location", containsString("52.1")))
                .andExpect(jsonPath("$.data.location", containsString("26.4")))
                .andExpect(jsonPath("$.data.areaKm2").doesNotExist());
    }

    @Test
    void provinceBoundaryIsReadBackAsMultiPolygon() throws Exception {
        Subject province = seedSubject(SubjectKind.PROVINCE);
        provinceRepository.save(Province.builder().subject(province)
                .boundary(WGS84.createMultiPolygon(new Polygon[] {square(55, 26)})).build());

        mockMvc.perform(get("/api/subjects/" + province.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boundary", startsWith("MULTIPOLYGON")))
                .andExpect(jsonPath("$.data.area").doesNotExist());
    }

    // A subject with no detail row reads as one with empty detail fields, not a failure.
    @Test
    void subjectWithoutDetailRowReadsWithEmptyDetail() throws Exception {
        Subject port = seedSubject(SubjectKind.PORT);

        mockMvc.perform(get("/api/subjects/" + port.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("PORT"))
                .andExpect(jsonPath("$.data.location").doesNotExist());
    }

    @Test
    void listFilteredByKindIsCaseInsensitiveAndReturnsOnlyThatKind() throws Exception {
        Subject fishingGround = seedSubject(SubjectKind.FISHING_GROUND);
        fishingGroundRepository.save(FishingGround.builder().subject(fishingGround).area(square(56, 26)).build());

        String body = mockMvc.perform(get("/api/subjects").param("kind", "fishing_ground").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(body, "$.data.items[*].kind");
        assertThat(kinds).isNotEmpty().containsOnly("FISHING_GROUND");
    }

    @Test
    void unknownSubjectIsNotFound() throws Exception {
        mockMvc.perform(get("/api/subjects/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    @Test
    void unknownKindFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/subjects").param("kind", "SEA_MONSTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
    }

    @Test
    void pageSizeAboveTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/api/subjects").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // The moderator direct create is gone: not even a moderator can mint a subject without a
    // proposal, and nothing is written.
    @Test
    void directSubjectCreateIsGoneEvenForAModerator() throws Exception {
        seedModerator("sfmod1", "sf-mod1@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod1@example.com");
        Cookie csrfCookie = fetchCsrfCookie();
        long before = subjectRepository.count();

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PORT\"}"))
                .andExpect(status().isMethodNotAllowed());
        assertThat(subjectRepository.count()).isEqualTo(before);
    }

    // The public-read rule names GET explicitly, and phase 3 added GET /api/subject-revisions/**
    // to it. Neither may have opened anything else.
    @Test
    void openingPublicReadsDidNotOpenAnyOtherProtectedRoute() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/expert-reviewer/applications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void existingErrorHandlersStillMapToTheirOwnCodes() throws Exception {
        LoginRequest badLogin = new LoginRequest("sf-nobody@example.com", "Wrong-Horse1!");
        mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}

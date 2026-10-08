package com.persiangulfwiki.core.subject;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRedirect;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/subject-kinds/counts, the home page's headline numbers. The subjects table is shared
// with every other test, so counts are asserted as deltas around rows seeded here.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectKindCountIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SubjectRedirectRepository subjectRedirectRepository;

    private Map<String, Integer> counts() throws Exception {
        String body = mockMvc.perform(get("/api/subject-kinds/counts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(body, "$.data[*].kind");
        List<Integer> values = JsonPath.read(body, "$.data[*].count");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < kinds.size(); i++) {
            counts.put(kinds.get(i), values.get(i));
        }
        return counts;
    }

    private Subject seed(SubjectKind kind) {
        return subjectRepository.save(Subject.builder().kind(kind).build());
    }

    @Test
    void countsLiveSubjectsAndSkipsDeletedAndMergedOnes() throws Exception {
        int before = counts().get("STRAIT");

        Subject kept = seed(SubjectKind.STRAIT);
        seed(SubjectKind.STRAIT);
        Subject deleted = seed(SubjectKind.STRAIT);
        deleted.setDeletedAt(Instant.now());
        subjectRepository.save(deleted);
        Subject merged = seed(SubjectKind.STRAIT);
        merged.setDeletedAt(Instant.now());
        subjectRepository.save(merged);
        subjectRedirectRepository.save(SubjectRedirect.builder().sourceId(merged.getId()).targetId(kept.getId()).build());

        assertThat(counts().get("STRAIT")).isEqualTo(before + 2);
    }

    @Test
    void everyKindIsListedAnonymouslyWithItsLocalizedName() throws Exception {
        mockMvc.perform(get("/api/subject-kinds/counts").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(SubjectKind.values().length))
                .andExpect(jsonPath("$.data[?(@.kind == 'PORT')].name.value").value("Ports"))
                .andExpect(jsonPath("$.data[?(@.kind == 'PORT')].name.isFallback").value(false));

        assertThat(counts().keySet())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(SubjectKind.values()).map(Enum::name).toList());
    }

    // Regression: the counts reuse the listing's kind-facet query, so the two must keep agreeing,
    // and the existing route under the same prefix must still resolve.
    @Test
    void countsMatchTheListingKindFacet() throws Exception {
        seed(SubjectKind.PORT);
        Map<String, Integer> counts = counts();

        String listing = mockMvc.perform(get("/api/subjects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(listing, "$.data.facets[?(@.key == 'kind')].options[*].code");
        List<Integer> facetCounts = JsonPath.read(listing, "$.data.facets[?(@.key == 'kind')].options[*].count");
        Map<String, Integer> facet = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            facet.put(codes.get(i), facetCounts.get(i));
        }
        assertThat(counts).isEqualTo(facet);

        mockMvc.perform(get("/api/subject-kinds/ISLAND/attributes"))
                .andExpect(status().isOk());
    }
}

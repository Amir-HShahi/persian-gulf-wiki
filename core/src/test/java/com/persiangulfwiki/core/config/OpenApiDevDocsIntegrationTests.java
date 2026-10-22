package com.persiangulfwiki.core.config;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiDevDocsIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eachDevFixtureTagFollowsTheFeatureItMints() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> tagNames = JsonPath.read(spec, "$.tags[*].name");
        assertThat(tagNames).containsExactly(
                "Authentication", "OAuth2", "Email Verification", "Password Management",
                "User Management", "Dev Test Users",
                "Admin", "Expert Reviewer",
                "Subjects", "Dev Test Subjects",
                "Subject Revisions", "Dev Test Subject Revisions",
                "Expert Verifications", "Dev Test Expert Verifications",
                "Attributes", "Dev Test Attributes",
                "Labels", "Dev Test Labels",
                "Sources", "Dev Test Sources",
                "Articles", "Dev Test Articles",
                "Featured Articles", "Dev Test Featured Articles",
                "Article Media", "Dev Test Media",
                "Topics", "Dev Test Topics",
                "Moderation", "Dev Test Moderation Tasks",
                "Marine Data", "Dev Test Marine Data",
                "Dev Demo Content");
    }

    // The dev spec is a superset of the public one, so this sweep covers every enum in both. A value
    // list alone tells a client nothing about what each value means, hence one bullet per value.
    @Test
    void everyEnumExplainsEachOfItsValues() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> enumSchemas = JsonPath.read(spec, "$..[?(@.enum)]");
        assertThat(enumSchemas).isNotEmpty().allSatisfy(schema -> {
            List<?> values = (List<?>) schema.get("enum");
            assertThat((String) schema.get("description")).as("description of enum %s", values)
                    .isNotNull()
                    .contains(values.stream().map(value -> "- `" + value + "`: ").toList());
        });
    }
}

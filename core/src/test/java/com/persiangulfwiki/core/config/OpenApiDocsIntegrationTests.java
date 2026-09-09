package com.persiangulfwiki.core.config;

import com.persiangulfwiki.core.TestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the shape of the generated OpenAPI document — specifically that responses are documented
 * as the bare payload rather than the {@code ApiResult} envelope they are actually sent in.
 * See {@link ApiResultSchemaConverter}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsIntegrationTests {

    private static final String REGISTER_201 = "$.paths['/api/auth/register'].post.responses['201']";
    private static final String SESSIONS_200 = "$.paths['/api/users/me/sessions'].get.responses['200']";
    private static final String LOGIN_200 = "$.paths['/api/auth/login'].post.responses['200']";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsAreServedPubliclyAndDescribeTheKnownEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/users'].get").exists());
    }

    @Test
    void objectPayloadIsDocumentedUnwrapped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(REGISTER_201 + ".content['*/*'].schema.$ref")
                        .value("#/components/schemas/RegisterResponse"));
    }

    @Test
    void listPayloadIsDocumentedAsAnArrayOfTheElementType() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SESSIONS_200 + ".content['*/*'].schema.type").value("array"))
                .andExpect(jsonPath(SESSIONS_200 + ".content['*/*'].schema.items.$ref")
                        .value("#/components/schemas/SessionResponse"));
    }

    @Test
    void emptyPayloadIsDocumentedWithNoSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(LOGIN_200).exists())
                .andExpect(jsonPath(LOGIN_200 + ".content['*/*'].schema").doesNotExist());
    }

    @Test
    void envelopeIsNotEmittedAsASchemaOfItsOwn() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ApiResult").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ApiResultRegisterResponse").doesNotExist());
    }

    @Test
    void envelopeIsDocumentedOnceInTheApiDescription() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.description")
                        .value(org.hamcrest.Matchers.containsString("Response envelope")))
                .andExpect(jsonPath("$.info.description")
                        .value(org.hamcrest.Matchers.containsString("\"data\"")));
    }

    @Test
    void logoIsAdvertisedOnTheInfoObject() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info['x-logo'].url").value("/docs/logo.svg"))
                .andExpect(jsonPath("$.info['x-logo'].altText").value("Persian Gulf Wiki"));
    }

    @Test
    void stagingIsTheFirstServerOffered() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].description").value("Staging"))
                .andExpect(jsonPath("$.servers[0].url")
                        .value("https://pgw-staging-api.ravensandrunes.me"))
                .andExpect(jsonPath("$.servers[1].description").value("Production"))
                .andExpect(jsonPath("$.servers[2].description").value("Local"));
    }
}

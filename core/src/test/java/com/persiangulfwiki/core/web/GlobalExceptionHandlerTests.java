package com.persiangulfwiki.core.web;

import com.persiangulfwiki.core.TestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Confirms every error response has the same shape even for the paths that never touch a
// GlobalExceptionHandler @ExceptionHandler method directly: a malformed request body (400,
// resolved by Spring's own HttpMessageNotReadableException handling) goes through
// GlobalExceptionHandler#handleExceptionInternal, and an unauthenticated request — including one
// to a route that doesn't exist at all, since anyRequest().authenticated() rejects it before
// DispatcherServlet ever resolves a 404 — goes through ProblemDetailAuthenticationEntryPoint.
//
// "type" is deliberately not asserted here: Spring's ProblemDetail Jackson serializer omits it
// whenever it's the RFC 7807 default (about:blank), which is every response in this app since
// nothing sets a real type URI.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTests {

    @Autowired
    private MockMvc mockMvc;

    private static final org.springframework.test.web.servlet.ResultMatcher[] COMMON_FIELDS = {
            jsonPath("$.title").exists(),
            jsonPath("$.status").exists(),
            jsonPath("$.detail").exists(),
            jsonPath("$.instance").exists(),
            jsonPath("$.code").exists(),
            jsonPath("$.timestamp").exists(),
            jsonPath("$.traceId").exists()
    };

    @Test
    void unmatchedRouteReturnsConsistentShape() throws Exception {
        var result = mockMvc.perform(get("/api/this-route-does-not-exist"))
                .andExpect(status().isUnauthorized());
        for (var matcher : COMMON_FIELDS) {
            result.andExpect(matcher);
        }
    }

    @Test
    void malformedRequestBodyReturnsConsistentShape() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest());
        for (var matcher : COMMON_FIELDS) {
            result.andExpect(matcher);
        }
    }

    @Test
    void unauthenticatedRequestReturnsConsistentShape() throws Exception {
        var result = mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
        for (var matcher : COMMON_FIELDS) {
            result.andExpect(matcher);
        }
    }
}

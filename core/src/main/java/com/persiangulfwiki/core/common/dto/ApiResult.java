package com.persiangulfwiki.core.common.dto;

// Named ApiResult, not ApiResponse, to avoid colliding with the unqualified
// io.swagger.v3.oas.annotations.responses.ApiResponse import already used in every controller.
//
// This envelope is deliberately invisible in the OpenAPI docs: ApiResultSchemaConverter rewrites
// every ApiResult<T> response schema to plain T, and the envelope is described once in the API
// description in OpenApiConfig. Nothing about the wire format changes — only how it's documented.
public record ApiResult<T>(T data, String message) {

    public static <T> ApiResult<T> of(T data, String message) {
        return new ApiResult<>(data, message);
    }

    public static ApiResult<Void> ofMessage(String message) {
        return new ApiResult<>(null, message);
    }
}

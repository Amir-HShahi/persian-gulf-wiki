package com.persiangulfwiki.core.common.dto;

// Named ApiResult, not ApiResponse, to avoid colliding with the unqualified
// io.swagger.v3.oas.annotations.responses.ApiResponse import already used in every controller.
public record ApiResult<T>(T data, String message) {

    public static <T> ApiResult<T> of(T data, String message) {
        return new ApiResult<>(data, message);
    }

    public static ApiResult<Void> ofMessage(String message) {
        return new ApiResult<>(null, message);
    }
}

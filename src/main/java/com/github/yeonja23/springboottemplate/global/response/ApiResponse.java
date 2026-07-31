package com.github.yeonja23.springboottemplate.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final T data;
    private final String code;
    private final String message;

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(null, null, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(null, code, message);
    }
}

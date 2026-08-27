package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI's error envelope: {@code {"error": {"message": ..., "type": ..., "param": ..., "code":
 * ...}}}.
 *
 * <p>why mirror it exactly: OpenAI SDKs parse this shape to raise typed exceptions. A gateway that
 * returns Spring Boot's default error body would turn every failure into an opaque parse error on
 * the client side, which is precisely the debugging experience a control plane is supposed to
 * prevent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponseDto(ErrorBody error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String message, String type, String param, String code) {}

    public static ErrorResponseDto of(String message, String type, String code) {
        return new ErrorResponseDto(new ErrorBody(message, type, null, code));
    }

    public static ErrorResponseDto of(String message, String type, String param, String code) {
        return new ErrorResponseDto(new ErrorBody(message, type, param, code));
    }
}

package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageDto(
        @NotBlank(message = "message role is required") String role,
        @NotNull(message = "message content is required") String content) {}

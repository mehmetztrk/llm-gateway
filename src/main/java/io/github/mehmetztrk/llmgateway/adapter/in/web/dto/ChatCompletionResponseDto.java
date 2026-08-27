package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The OpenAI {@code /v1/chat/completions} response body. Field names and the constant
 * {@code "chat.completion"} object type are dictated by the SDKs that parse this — they are a
 * contract, not a style choice.
 */
public record ChatCompletionResponseDto(
        String id, String object, long created, String model, List<ChoiceDto> choices, UsageDto usage) {

    public static final String OBJECT_TYPE = "chat.completion";

    public record ChoiceDto(
            int index,
            MessageDto message,
            @JsonProperty("finish_reason") String finishReason) {}

    public record UsageDto(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}
}

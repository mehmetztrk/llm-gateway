package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One {@code data:} frame of an OpenAI streamed response.
 *
 * <p>The {@code object} discriminator is {@code chat.completion.chunk} rather than
 * {@code chat.completion} — SDKs branch on it, and getting it wrong makes a stream parse as a
 * malformed single response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunkDto(
        String id, String object, long created, String model, List<ChunkChoiceDto> choices, UsageDto usage) {

    public static final String OBJECT_TYPE = "chat.completion.chunk";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoiceDto(
            int index,
            DeltaDto delta,
            @JsonProperty("finish_reason") String finishReason) {}

    /**
     * The incremental payload. {@code role} appears only on the first frame, which is what OpenAI
     * does and what SDKs expect when they assemble the message.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeltaDto(String role, String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageDto(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}
}

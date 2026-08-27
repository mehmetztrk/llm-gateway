package io.github.mehmetztrk.llmgateway.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionChunkDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ErrorResponseDto;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.error.FeatureNotSupported;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Turns domain chunks into OpenAI-shaped SSE frames.
 *
 * <p>why serialise to a String here instead of handing DTOs to Spring's SSE encoder: the stream has
 * to end with the literal frame {@code data: [DONE]}, which is not JSON. Mixing an encoded object
 * type and a raw sentinel in one publisher makes the emitted bytes depend on which encoder Spring
 * picks per element. Producing strings ourselves makes the output exactly predictable — which for a
 * gateway, whose entire job is to be byte-compatible with someone else's API, is worth more than
 * the convenience.
 */
final class SseChunkWriter {

    static final String DONE_SENTINEL = "[DONE]";

    private final ObjectMapper objectMapper;

    SseChunkWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param first whether this is the first frame of the stream, which is the only one carrying
     *     {@code delta.role}
     */
    ServerSentEvent<String> toEvent(CompletionChunk chunk, boolean first) {
        ChatCompletionChunkDto dto =
                switch (chunk) {
                    case CompletionChunk.Delta delta ->
                        new ChatCompletionChunkDto(
                                delta.id(),
                                ChatCompletionChunkDto.OBJECT_TYPE,
                                delta.createdAt().getEpochSecond(),
                                delta.model(),
                                List.of(new ChatCompletionChunkDto.ChunkChoiceDto(
                                        0,
                                        new ChatCompletionChunkDto.DeltaDto(
                                                first ? "assistant" : null, delta.content()),
                                        null)),
                                null);
                    case CompletionChunk.Done done ->
                        new ChatCompletionChunkDto(
                                done.id(),
                                ChatCompletionChunkDto.OBJECT_TYPE,
                                done.createdAt().getEpochSecond(),
                                done.model(),
                                List.of(new ChatCompletionChunkDto.ChunkChoiceDto(
                                        0,
                                        new ChatCompletionChunkDto.DeltaDto(null, null),
                                        done.finishReason().wireValue())),
                                done.usage().totalTokens() == 0
                                        ? null
                                        : new ChatCompletionChunkDto.UsageDto(
                                                done.usage().promptTokens(),
                                                done.usage().completionTokens(),
                                                done.usage().totalTokens()));
                };
        return event(write(dto));
    }

    /** The terminating frame. Emitted only when the stream completed normally. */
    ServerSentEvent<String> done() {
        return event(DONE_SENTINEL);
    }

    /**
     * A failure that happened after the response headers were already sent.
     *
     * <p>The status code is long gone by then — the client has a 200 and a half-written body — so
     * the only honest thing left is to say so inside the stream and close. Silently completing
     * would let the caller believe it received the whole answer.
     */
    ServerSentEvent<String> error(Throwable throwable) {
        ErrorResponseDto body =
                switch (throwable) {
                    case ModelNotFound e ->
                        ErrorResponseDto.of(e.getMessage(), "invalid_request_error", "model_not_found");
                    case ModelNotAllowed e ->
                        ErrorResponseDto.of(e.getMessage(), "invalid_request_error", "model_not_allowed");
                    case FeatureNotSupported e ->
                        ErrorResponseDto.of(e.getMessage(), "invalid_request_error", "unsupported_parameter");
                    case ProviderCallFailed e ->
                        ErrorResponseDto.of("The upstream provider failed mid-stream.", "api_error", "provider_error");
                    case GatewayException e -> ErrorResponseDto.of(e.getMessage(), "api_error", "internal_error");
                    default -> ErrorResponseDto.of("Internal error.", "api_error", "internal_error");
                };
        return event(write(body));
    }

    private ServerSentEvent<String> event(String data) {
        return ServerSentEvent.<String>builder().data(data).build();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Serialising our own records cannot realistically fail; if it somehow does, an
            // unchecked throw is correct because the stream is unrecoverable at that point.
            throw new IllegalStateException("failed to serialise SSE frame", e);
        }
    }
}

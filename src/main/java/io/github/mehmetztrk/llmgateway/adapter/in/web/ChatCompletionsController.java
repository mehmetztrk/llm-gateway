package io.github.mehmetztrk.llmgateway.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionRequestDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionResponseDto;
import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The OpenAI-compatible entry point.
 *
 * <p>Deliberately thin: parse, translate, delegate, translate back. Every decision worth testing
 * lives behind {@link ChatCompletionUseCase}.
 *
 * <p><b>Not yet authenticated.</b> API keys and tenants arrive in M3; until then this endpoint is
 * open. That is a known, temporary state, not an oversight.
 */
@RestController
@RequestMapping("/v1")
class ChatCompletionsController {

    private final ChatCompletionUseCase chatCompletion;
    private final SseChunkWriter sseWriter;

    ChatCompletionsController(ChatCompletionUseCase chatCompletion, ObjectMapper objectMapper) {
        this.chatCompletion = chatCompletion;
        this.sseWriter = new SseChunkWriter(objectMapper);
    }

    /**
     * why {@code ResponseEntity<?>} with two branches rather than two mapped endpoints: OpenAI puts
     * both behaviours on the same path and discriminates on a body field, so any client that flips
     * {@code stream} must keep working without changing its URL.
     */
    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    ResponseEntity<?> createChatCompletion(@Valid @RequestBody ChatCompletionRequestDto request) {
        ChatRequest domainRequest = OpenAiMapper.toDomain(request);

        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(streamOf(domainRequest));
        }

        Mono<ChatCompletionResponseDto> body =
                chatCompletion.complete(domainRequest).map(OpenAiMapper::toWire);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * There are two completely different kinds of failure in a streamed response, and conflating
     * them is the classic bug in gateways that stream.
     *
     * <ul>
     *   <li><b>Before the first chunk</b> — an unknown model, a rejected key, a provider that
     *       refuses the connection. Nothing has been written, the response is uncommitted, and the
     *       honest answer is a real HTTP status: 404, 401, 502. An SDK then raises its typed error.
     *   <li><b>After the first chunk</b> — the status line is long gone and the client already
     *       holds a 200 and part of an answer. The only place left to report the failure is inside
     *       the stream.
     * </ul>
     *
     * <p>{@code switchOnFirst} is what lets one pipeline serve both: it exposes the first signal
     * before deciding how the rest of the stream behaves. An error in that first signal is
     * re-thrown so {@link GatewayErrorHandler} maps it to a status code; everything after it is
     * caught and turned into an in-band error frame.
     */
    private Flux<ServerSentEvent<String>> streamOf(ChatRequest request) {
        return chatCompletion.stream(request).switchOnFirst((firstSignal, chunks) -> {
            if (firstSignal.isOnError()) {
                return Flux.error(firstSignal.getThrowable());
            }
            return chunks
                    // index() pairs each element with its position, which is how the first frame
                    // knows to carry delta.role without the domain modelling "firstness".
                    .index()
                    .map(indexed -> sseWriter.toEvent(indexed.getT2(), indexed.getT1() == 0L))
                    .concatWith(Flux.just(sseWriter.done()))
                    // After concatWith on purpose: when the source errors, concatWith never runs,
                    // so a failed stream ends with an error frame and no [DONE]. That absence is
                    // how a client distinguishes a truncated answer from a complete one.
                    .onErrorResume(error -> Flux.just(sseWriter.error(error)));
        });
    }
}

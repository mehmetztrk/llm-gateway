package io.github.mehmetztrk.llmgateway.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionRequestDto;
import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * <p>The {@link AuthenticatedCaller} is resolved by {@code ApiKeyAuthenticationFilter} and injected
 * here by Spring Security. It is a required parameter rather than something looked up later, so a
 * request can never reach a provider without a tenant attached to it.
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
     * why {@code Mono<ResponseEntity<?>>} rather than two mapped endpoints: OpenAI puts both
     * behaviours on the same path and discriminates on a body field, so any client that flips
     * {@code stream} must keep working without changing its URL. The Mono is needed because
     * admission control is asynchronous, and its outcome decides both the status and the headers.
     */
    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    Mono<ResponseEntity<?>> createChatCompletion(
            @AuthenticationPrincipal AuthenticatedCaller caller, @Valid @RequestBody ChatCompletionRequestDto request) {
        ChatRequest domainRequest = OpenAiMapper.toDomain(request);

        if (Boolean.TRUE.equals(request.stream())) {
            return chatCompletion.stream(caller, domainRequest)
                    .map(result -> ResponseEntity.ok()
                            .headers(headers -> RateLimitHeaders.apply(headers, result))
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(streamOf(result.body())));
        }

        return chatCompletion
                .complete(caller, domainRequest)
                .map(result -> ResponseEntity.ok()
                        .headers(headers -> RateLimitHeaders.apply(headers, result))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(OpenAiMapper.toWire(result.body())));
    }

    /**
     * There are two completely different kinds of failure in a streamed response, and conflating
     * them is the classic bug in gateways that stream.
     *
     * <ul>
     *   <li><b>Before the first chunk</b> — an unknown model, a rejected key, a rate limit, a
     *       provider that refuses the connection. Nothing has been written, the response is
     *       uncommitted, and the honest answer is a real HTTP status: 404, 401, 429, 502. An SDK
     *       then raises its typed error.
     *   <li><b>After the first chunk</b> — the status line is long gone and the client already
     *       holds a 200 and part of an answer. The only place left to report the failure is inside
     *       the stream.
     * </ul>
     *
     * <p>Admission failures land in the first category automatically: they happen in the outer Mono,
     * before this method is ever called. {@code switchOnFirst} covers the same distinction for
     * failures that originate in the provider.
     */
    private Flux<ServerSentEvent<String>> streamOf(Flux<CompletionChunk> chunks) {
        return chunks.switchOnFirst((firstSignal, rest) -> {
            if (firstSignal.isOnError()) {
                return Flux.error(firstSignal.getThrowable());
            }
            return rest
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

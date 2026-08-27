package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionRequestDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ChatCompletionResponseDto;
import io.github.mehmetztrk.llmgateway.application.port.in.ChatCompletionUseCase;
import io.github.mehmetztrk.llmgateway.domain.error.FeatureNotSupported;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The OpenAI-compatible entry point.
 *
 * <p>Deliberately thin: parse, translate, delegate, translate back. Every decision worth testing
 * lives behind {@link ChatCompletionUseCase}, so this class needs no test of its own beyond the
 * wire-contract tests.
 *
 * <p><b>Not yet authenticated.</b> API keys and tenants arrive in M3; until then this endpoint is
 * open. That is a known, temporary state, not an oversight.
 */
@RestController
@RequestMapping("/v1")
class ChatCompletionsController {

    private final ChatCompletionUseCase chatCompletion;

    ChatCompletionsController(ChatCompletionUseCase chatCompletion) {
        this.chatCompletion = chatCompletion;
    }

    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<ChatCompletionResponseDto> createChatCompletion(@Valid @RequestBody ChatCompletionRequestDto request) {
        if (Boolean.TRUE.equals(request.stream())) {
            // Refuse rather than silently returning a non-streamed body: a client that asked for a
            // stream and got a single JSON object fails somewhere far from the cause. M2 implements
            // this properly.
            return Mono.error(new FeatureNotSupported(
                    "stream", "server-sent-event streaming lands in M2; send stream=false for now"));
        }

        return Mono.fromCallable(() -> OpenAiMapper.toDomain(request))
                .flatMap(chatCompletion::complete)
                .map(OpenAiMapper::toWire);
    }
}

package io.github.mehmetztrk.llmgateway.application.port.in;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.CompletionChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the gateway offers the outside world for a non-streamed completion.
 *
 * <p>why an inbound port at all, when there is exactly one implementation: it is the contract the
 * web adapter is written against, so the controller cannot reach past it into provider internals,
 * and a test can drive the use case without an HTTP layer.
 */
public interface ChatCompletionUseCase {

    Mono<Completion> complete(ChatRequest request);

    /** The same request, delivered incrementally. See {@code LlmProvider#stream}. */
    Flux<CompletionChunk> stream(ChatRequest request);
}

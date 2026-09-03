package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** The SSE wire contract: the exact bytes an OpenAI SDK will parse. */
@TestPropertySource(
        properties = {
            "gateway.providers.mock.completion-tokens=6",
            // Caching off: these tests repeat identical requests on purpose, and a cache hit
            // would mean the provider is never reached — which is the cache working, not this
            // behaviour being broken. Each test should fail for one reason only.
            "gateway.cache.enabled=false"
        })
class ChatCompletionsStreamApiIT extends AbstractGatewayIT {

    private List<String> streamFrames() {
        return asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("responds with text/event-stream and terminates with [DONE]")
    void streamsFramesAndTerminates() {
        List<String> frames = streamFrames();

        assertThat(frames).isNotNull().hasSizeGreaterThan(2);
        assertThat(frames.getLast()).isEqualTo("[DONE]");
        assertThat(frames.subList(0, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).contains("\"object\":\"chat.completion.chunk\""));
    }

    @Test
    @DisplayName("only the first frame carries delta.role, as OpenAI does")
    void onlyFirstFrameCarriesRole() {
        List<String> frames = streamFrames();

        assertThat(frames.getFirst()).contains("\"role\":\"assistant\"");
        assertThat(frames.subList(1, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).doesNotContain("\"role\""));
    }

    @Test
    @DisplayName("the penultimate frame carries finish_reason and the token usage")
    void finalChunkCarriesFinishReasonAndUsage() {
        List<String> frames = streamFrames();

        String terminal = frames.get(frames.size() - 2);
        assertThat(terminal).contains("\"finish_reason\":\"stop\"");
        assertThat(terminal).contains("\"completion_tokens\":6");
    }

    @Test
    @DisplayName("reassembling the deltas yields the same text the non-streamed call returns")
    void deltasReassembleToTheWholeAnswer() {
        String assembled = streamFrames().stream()
                .filter(frame -> frame.contains("\"content\":"))
                .map(ChatCompletionsStreamApiIT::extractContent)
                .reduce("", String::concat);

        String wholeBody = asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(assembled).isNotBlank();
        assertThat(extractContent(wholeBody))
                .as("streamed and non-streamed answers must not diverge")
                .isEqualTo(assembled);
    }

    @Test
    @DisplayName("a pre-stream failure is a normal JSON error, because no frame was written yet")
    void preStreamFailureIsPlainJson() {
        asTenant()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"nobody-serves-this","messages":[{"role":"user","content":"hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("model_not_found");
    }

    private static String extractContent(String frame) {
        int start = frame.indexOf("\"content\":\"");
        if (start < 0) {
            return "";
        }
        start += "\"content\":\"".length();
        int end = frame.indexOf('"', start);
        return end < 0 ? "" : frame.substring(start, end);
    }
}

package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The SSE wire contract. Everything here is about the exact bytes an OpenAI SDK will parse.
 *
 * <p>The mid-stream failure case is configured through properties rather than by unplugging
 * anything, which is the whole reason MockProvider has a {@code failAfterChunks} knob.
 */
@SpringBootTest
@AutoConfigureWebTestClient(timeout = "30s")
@TestPropertySource(
        properties = {"gateway.providers.mock.completion-tokens=6", "gateway.providers.mock.models=mock-fast"})
class ChatCompletionsStreamApiTest {

    @Autowired
    private WebTestClient client;

    private List<String> streamFrames(String body) {
        return client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
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
        List<String> frames = streamFrames("""
                {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                """);

        assertThat(frames).isNotNull().hasSizeGreaterThan(2);
        assertThat(frames.getLast()).isEqualTo("[DONE]");
        assertThat(frames.subList(0, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).contains("\"object\":\"chat.completion.chunk\""));
    }

    @Test
    @DisplayName("only the first frame carries delta.role, as OpenAI does")
    void onlyFirstFrameCarriesRole() {
        List<String> frames = streamFrames("""
                {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                """);

        assertThat(frames.getFirst()).contains("\"role\":\"assistant\"");
        assertThat(frames.subList(1, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).doesNotContain("\"role\""));
    }

    @Test
    @DisplayName("the penultimate frame carries finish_reason and the token usage")
    void finalChunkCarriesFinishReasonAndUsage() {
        List<String> frames = streamFrames("""
                {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                """);

        String terminal = frames.get(frames.size() - 2);
        assertThat(terminal).contains("\"finish_reason\":\"stop\"");
        assertThat(terminal).contains("\"completion_tokens\":6");
        assertThat(terminal).contains("\"total_tokens\"");
    }

    @Test
    @DisplayName("reassembling the deltas yields the same text the non-streamed call returns")
    void deltasReassembleToTheWholeAnswer() {
        List<String> frames = streamFrames("""
                {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                """);

        String assembled = frames.stream()
                .filter(frame -> frame.contains("\"content\":"))
                .map(ChatCompletionsStreamApiTest::extractContent)
                .reduce("", String::concat);

        String wholeBody = client.post()
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
        client.post()
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

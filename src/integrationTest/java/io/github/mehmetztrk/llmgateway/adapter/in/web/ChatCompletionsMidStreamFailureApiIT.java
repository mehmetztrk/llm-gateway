package io.github.mehmetztrk.llmgateway.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mehmetztrk.llmgateway.AbstractGatewayIT;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * What happens when a provider dies after the response has already started.
 *
 * <p>This is the case that separates a real streaming implementation from a demo. The status line
 * was written and flushed long ago, so there is no 502 left to send.
 *
 * <p>Its own class because {@code fail-after-chunks} is a provider-wide setting: a mock that always
 * dies would make every other test in the class meaningless.
 */
@TestPropertySource(
        properties = {"gateway.providers.mock.completion-tokens=50", "gateway.providers.mock.fail-after-chunks=3"})
class ChatCompletionsMidStreamFailureApiIT extends AbstractGatewayIT {

    private List<String> streamFrames(Duration timeout) {
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
                .isEqualTo(HttpStatus.OK)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(timeout);
    }

    @Test
    @DisplayName("the partial content is kept, an error frame is appended, and [DONE] is withheld")
    void emitsErrorFrameAndWithholdsDone() {
        List<String> frames = streamFrames(Duration.ofSeconds(30));

        assertThat(frames).isNotNull().hasSize(4);

        // The three chunks generated before the failure are delivered, not discarded.
        assertThat(frames.subList(0, 3))
                .allSatisfy(frame -> assertThat(frame).contains("\"object\":\"chat.completion.chunk\""));

        // The failure is reported in-band, in the same envelope the SDK already knows.
        assertThat(frames.getLast()).contains("\"error\"").contains("\"code\":\"provider_error\"");

        // No [DONE]: that sentinel is a client's only signal that it received the whole answer.
        // Emitting it after a failure would turn a truncated response into a silent one.
        assertThat(frames).noneSatisfy(frame -> assertThat(frame).isEqualTo("[DONE]"));
    }

    @Test
    @DisplayName("the stream closes cleanly rather than hanging the client")
    void streamCompletes() {
        // If the publisher never terminated, collectList would block until the timeout and this
        // test would fail — which is the assertion.
        assertThat(streamFrames(Duration.ofSeconds(10))).isNotEmpty();
    }
}

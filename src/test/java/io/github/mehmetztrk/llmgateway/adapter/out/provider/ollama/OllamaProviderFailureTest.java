package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * How the Ollama adapter behaves when the upstream misbehaves. Every one of these must surface as
 * {@link ProviderCallFailed} — the failover logic in M5 keys off that type, and a leaked
 * {@code WebClientResponseException} would slip straight past it.
 */
class OllamaProviderFailureTest {

    private static final String MODEL = "qwen2.5:1.5b-instruct";

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OllamaProvider provider(Duration timeout) {
        return new OllamaProvider(
                ProviderId.of("ollama-primary"),
                WebClient.builder().baseUrl(WIRE_MOCK.baseUrl()).build(),
                Set.of(MODEL),
                timeout);
    }

    private ChatRequest request() {
        return ChatRequest.of(MODEL, ChatMessage.user("hello"));
    }

    @Test
    @DisplayName("upstream 500 becomes ProviderCallFailed carrying the provider id")
    void upstreamServerErrorIsWrapped() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        StepVerifier.create(provider(Duration.ofSeconds(5)).complete(request()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderCallFailed.class);
                    assertThat(((ProviderCallFailed) error).provider()).isEqualTo(ProviderId.of("ollama-primary"));
                    assertThat(error).hasMessageContaining("500");
                })
                .verify();
    }

    @Test
    @DisplayName("a stalled upstream is cut off by the configured timeout")
    void slowUpstreamTimesOut() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2_000)
                        .withBody("{}")));

        StepVerifier.create(provider(Duration.ofMillis(200)).complete(request()))
                .expectError(ProviderCallFailed.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a response with no choices is rejected rather than half-mapped")
    void emptyChoicesIsRejected() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"x\",\"model\":\"" + MODEL + "\",\"choices\":[]}")));

        StepVerifier.create(provider(Duration.ofSeconds(5)).complete(request()))
                .expectErrorSatisfies(error ->
                        assertThat(error).isInstanceOf(ProviderCallFailed.class).hasMessageContaining("no choices"))
                .verify();
    }

    @Test
    @DisplayName("a missing usage block degrades to zero rather than failing the call")
    void missingUsageDegradesGracefully() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"x","model":"qwen2.5:1.5b-instruct","choices":[
                                  {"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}
                                """)));

        StepVerifier.create(provider(Duration.ofSeconds(5)).complete(request()))
                .assertNext(completion -> {
                    assertThat(completion.usage().totalTokens()).isZero();
                    assertThat(completion.message().content()).isEqualTo("hi");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sends stream=false and the OpenAI-shaped body upstream")
    void sendsExpectedRequestBody() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"x","model":"qwen2.5:1.5b-instruct","choices":[
                                  {"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """)));

        ChatRequest request = new ChatRequest(MODEL, List.of(ChatMessage.user("hello")), 64, 0.2);
        provider(Duration.ofSeconds(5)).complete(request).block();

        WIRE_MOCK.verify(
                postRequestedFor(urlPathEqualTo("/v1/chat/completions")).withRequestBody(equalToJson("""
                        {
                          "model": "qwen2.5:1.5b-instruct",
                          "messages": [{"role":"user","content":"hello"}],
                          "stream": false,
                          "max_tokens": 64,
                          "temperature": 0.2
                        }
                        """)));
    }
}

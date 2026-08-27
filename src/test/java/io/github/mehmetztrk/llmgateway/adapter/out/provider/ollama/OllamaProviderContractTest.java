package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.provider.LlmProviderContract;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Runs the shared provider contract against a stubbed Ollama.
 *
 * <p>why WireMock rather than the real server: this suite runs in CI, where no GPU and no Ollama
 * exist, and it must fail for exactly one reason — our HTTP mapping being wrong. A live Ollama
 * would add model availability, warm-up time and hardware to the list of things that can turn it
 * red. The live server is exercised separately in {@code integrationTest}.
 */
class OllamaProviderContractTest extends LlmProviderContract {

    private static final String MODEL = "qwen2.5:1.5b-instruct";

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void stubChatCompletions() {
        // Both behaviours live on one path, so the stubs are told apart by the stream flag in the
        // request body — exactly how the real server distinguishes them.
        // why the stub matches the body's stream flag and not just the Accept header: an earlier
        // version matched on Accept alone, and happily served SSE to a request whose body said
        // stream=false. The adapter had exactly that bug, the stub hid it, and only the live
        // Ollama test caught it. A stub that accepts a request the real server would reject is
        // worse than no stub at all.
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Accept", containing("text/event-stream"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1767225600,\
                                "model":"qwen2.5:1.5b-instruct","choices":[{"index":0,\
                                "delta":{"role":"assistant","content":"A gateway "},"finish_reason":null}]}

                                data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1767225600,\
                                "model":"qwen2.5:1.5b-instruct","choices":[{"index":0,\
                                "delta":{"content":"sits in front of other services."},"finish_reason":null}]}

                                data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1767225600,\
                                "model":"qwen2.5:1.5b-instruct","choices":[{"index":0,"delta":{},\
                                "finish_reason":"stop"}],"usage":{"prompt_tokens":18,"completion_tokens":9,\
                                "total_tokens":27}}

                                data: [DONE]

                                """)));

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-123",
                                  "object": "chat.completion",
                                  "created": 1767225600,
                                  "model": "qwen2.5:1.5b-instruct",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "A gateway sits in front of other services."
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ],
                                  "usage": {
                                    "prompt_tokens": 18,
                                    "completion_tokens": 9,
                                    "total_tokens": 27
                                  },
                                  "an_unexpected_future_field": "must be ignored"
                                }
                                """)));
    }

    @Override
    protected LlmProvider provider() {
        return new OllamaProvider(
                ProviderId.of("ollama-primary"),
                WebClient.builder().baseUrl(WIRE_MOCK.baseUrl()).build(),
                Set.of(MODEL),
                Duration.ofSeconds(10),
                new ObjectMapper());
    }

    @Override
    protected String supportedModel() {
        return MODEL;
    }
}

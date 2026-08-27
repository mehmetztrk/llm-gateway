package io.github.mehmetztrk.llmgateway.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The wire contract an OpenAI SDK depends on. These assertions are about JSON field names and
 * status codes, not about business logic — if any of them changes, somebody's SDK breaks.
 *
 * <p>Runs against the mock provider, so it needs no Ollama, no GPU and no network.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class ChatCompletionsApiTest {

    @Autowired
    private WebTestClient client;

    private WebTestClient.ResponseSpec post(String body) {
        return client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    @DisplayName("returns an OpenAI-shaped chat completion")
    void returnsOpenAiShapedResponse() {
        post("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hello"}]}
                        """)
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id")
                .isNotEmpty()
                .jsonPath("$.object")
                .isEqualTo("chat.completion")
                .jsonPath("$.model")
                .isEqualTo("mock-fast")
                .jsonPath("$.created")
                .isNumber()
                .jsonPath("$.choices.length()")
                .isEqualTo(1)
                .jsonPath("$.choices[0].index")
                .isEqualTo(0)
                .jsonPath("$.choices[0].message.role")
                .isEqualTo("assistant")
                .jsonPath("$.choices[0].message.content")
                .isNotEmpty()
                .jsonPath("$.choices[0].finish_reason")
                .isEqualTo("stop")
                .jsonPath("$.usage.prompt_tokens")
                .isNumber()
                .jsonPath("$.usage.completion_tokens")
                .isNumber()
                .jsonPath("$.usage.total_tokens")
                .isNumber();
    }

    @Test
    @DisplayName("ignores parameters it does not implement instead of rejecting the request")
    void toleratesUnknownParameters() {
        // An SDK sends tools, seed, response_format, user and more. Rejecting any of them would
        // break the "just change base_url" promise.
        post("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],
                         "seed":7,"user":"u-1","response_format":{"type":"text"},"top_p":0.9}
                        """).expectStatus().isOk();
    }

    @Test
    @DisplayName("stream=true switches the same endpoint to server-sent events")
    void streamingSwitchesContentType() {
        post("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":true}
                        """).expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    @DisplayName("stream=false is accepted")
    void acceptsExplicitNonStreaming() {
        post("""
                        {"model":"mock-fast","messages":[{"role":"user","content":"hi"}],"stream":false}
                        """).expectStatus().isOk();
    }

    @Test
    @DisplayName("an unserved model returns 404 with OpenAI's model_not_found code")
    void unknownModelReturns404() {
        post("""
                        {"model":"gpt-5-turbo-imaginary","messages":[{"role":"user","content":"hi"}]}
                        """)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("model_not_found")
                .jsonPath("$.error.type")
                .isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("a missing model field is a 400 naming the offending field")
    void missingModelReturns400() {
        post("""
                {"messages":[{"role":"user","content":"hi"}]}
                """)
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("invalid_request_error")
                .jsonPath("$.error.param")
                .isEqualTo("model");
    }

    @Test
    @DisplayName("an empty messages array is a 400")
    void emptyMessagesReturns400() {
        post("""
                {"model":"mock-fast","messages":[]}
                """)
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.param")
                .isEqualTo("messages");
    }

    @Test
    @DisplayName("malformed JSON is a 400 in the OpenAI error envelope, not a Spring error page")
    void malformedJsonReturns400() {
        post("{not json at all")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.type")
                .isEqualTo("invalid_request_error");
    }
}

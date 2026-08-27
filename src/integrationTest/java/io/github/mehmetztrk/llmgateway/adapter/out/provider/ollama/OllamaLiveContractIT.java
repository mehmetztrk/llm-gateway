package io.github.mehmetztrk.llmgateway.adapter.out.provider.ollama;

import io.github.mehmetztrk.llmgateway.application.port.out.LlmProvider;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.provider.LlmProviderContract;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The same contract as {@link OllamaProviderContractTest}, but against a real Ollama.
 *
 * <p>why both: the WireMock version proves our mapping matches the schema we believe Ollama uses.
 * Only this one proves that belief is correct. Running just the stubbed version is how an adapter
 * ends up perfectly consistent with a format nobody actually speaks.
 *
 * <p>why skipped rather than failed when Ollama is absent: it must not turn CI red on a runner with
 * no GPU. A skip is honest — it says "unverified here" — whereas deleting the test would say
 * "verified" by omission. Run it locally with {@code docker compose up -d}.
 */
@EnabledIf("ollamaIsReachable")
class OllamaLiveContractIT extends LlmProviderContract {

    private static final String BASE_URL = System.getProperty("ollama.base-url", "http://localhost:11434");
    private static final String MODEL = System.getProperty("ollama.model", "qwen2.5:1.5b-instruct");

    static boolean ollamaIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 11434), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected LlmProvider provider() {
        return new OllamaProvider(
                ProviderId.of("ollama-primary"),
                WebClient.builder().baseUrl(BASE_URL).build(),
                Set.of(MODEL),
                // Generous: the first call after startup pays for loading the model into VRAM,
                // which on a 6 GB laptop GPU is tens of seconds.
                Duration.ofSeconds(120));
    }

    @Override
    protected String supportedModel() {
        return MODEL;
    }
}

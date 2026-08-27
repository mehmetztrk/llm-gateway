package io.github.mehmetztrk.llmgateway.domain.error;

/**
 * No configured provider serves the requested model. Maps to HTTP 404 with OpenAI's
 * {@code model_not_found} code, because that is what an OpenAI SDK is written to understand.
 */
public final class ModelNotFound extends GatewayException {

    private final String model;

    public ModelNotFound(String model) {
        super("The model '" + model + "' does not exist or is not served by any configured provider");
        this.model = model;
    }

    public String model() {
        return model;
    }
}

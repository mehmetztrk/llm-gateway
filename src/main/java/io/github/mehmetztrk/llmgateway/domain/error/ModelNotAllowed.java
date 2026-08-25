package io.github.mehmetztrk.llmgateway.domain.error;

/** Raised when a tenant requests a model that is not on its allow-list. Maps to HTTP 403. */
public final class ModelNotAllowed extends GatewayException {

    private final String model;

    public ModelNotAllowed(String model) {
        super("Model '" + model + "' is not allowed for this tenant");
        this.model = model;
    }

    public String model() {
        return model;
    }
}

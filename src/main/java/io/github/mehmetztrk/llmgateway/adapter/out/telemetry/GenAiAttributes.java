package io.github.mehmetztrk.llmgateway.adapter.out.telemetry;

/**
 * Attribute names from the OpenTelemetry GenAI semantic conventions.
 *
 * <p>why use the conventions rather than inventing names: a span called {@code llm.model} is
 * readable, and a span called {@code gen_ai.request.model} is <em>queryable</em> — by Grafana's
 * built-in GenAI panels, by any vendor's LLM observability view, and by whatever the next tool
 * turns out to be. Inventing names means every dashboard has to be written from scratch and every
 * future integration needs a translation layer.
 *
 * <p>Collected here as constants rather than scattered as string literals so that tracking a
 * convention revision is a one-file change.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">OTel GenAI semantic
 *     conventions</a>
 */
public final class GenAiAttributes {

    /** The provider family, e.g. {@code ollama}. */
    public static final String SYSTEM = "gen_ai.system";

    public static final String OPERATION_NAME = "gen_ai.operation.name";

    /** The model the client asked for — which, with aliases, is often not the one that ran. */
    public static final String REQUEST_MODEL = "gen_ai.request.model";

    public static final String REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    public static final String REQUEST_TEMPERATURE = "gen_ai.request.temperature";

    /** The model that actually produced the answer. Distinct from the request model on purpose. */
    public static final String RESPONSE_MODEL = "gen_ai.response.model";

    public static final String RESPONSE_ID = "gen_ai.response.id";
    public static final String RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
    public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    /**
     * Gateway-specific attributes, namespaced so they cannot be mistaken for standard ones. A
     * convention that later defines {@code gen_ai.tenant.id} must not silently collide with ours.
     */
    public static final String TENANT_ID = "llmgw.tenant.id";

    public static final String PROVIDER_ID = "llmgw.provider.id";
    public static final String CACHE_STATUS = "llmgw.cache.status";
    public static final String STREAMED = "llmgw.streamed";
    public static final String COST_MICROS = "llmgw.cost.micros";

    private GenAiAttributes() {}
}

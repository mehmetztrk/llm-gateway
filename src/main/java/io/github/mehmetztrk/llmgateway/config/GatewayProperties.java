package io.github.mehmetztrk.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Root of the {@code gateway.*} configuration tree.
 *
 * <p>why records, not classes with setters: configuration that can be mutated at runtime is a
 * debugging trap — a value read at startup may not be the value in effect an hour later. Records
 * give us binding-time validation and a permanently trustworthy snapshot.
 *
 * <p>why {@code @DefaultValue}: constructor binding has no field initialisers to fall back on, so
 * an absent YAML block would bind {@code null} and NPE at first use. {@code @DefaultValue} on the
 * nested record makes the whole block optional.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(@DefaultValue Observability observability) {

    /**
     * @param logPrompts when true, prompt and completion content is written to logs. Defaults to
     *     false and must stay false outside local debugging: prompts routinely carry customer PII,
     *     and a gateway is exactly the chokepoint where that would be centralised. See README
     *     "Privacy".
     */
    public record Observability(@DefaultValue("false") boolean logPrompts) {}
}

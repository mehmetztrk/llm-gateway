package io.github.mehmetztrk.llmgateway.application.port.out;

/**
 * Mints new API keys.
 *
 * <p>A port because key generation needs a CSPRNG and a wire format, both of which are adapter
 * concerns — and because the application layer may not import from {@code adapter}.
 */
public interface ApiKeyFactory {

    /** A fresh key with enough entropy that guessing is not a threat model. */
    String generate();

    /** The leading fragment safe to store and display afterwards. */
    String displayPrefix(String key);
}

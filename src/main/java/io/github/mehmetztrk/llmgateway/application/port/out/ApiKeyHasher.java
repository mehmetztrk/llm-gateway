package io.github.mehmetztrk.llmgateway.application.port.out;

/**
 * Turns a presented API key into the digest stored in the database.
 *
 * <p>A port rather than a static utility so the hashing scheme — and its pepper — is injected
 * configuration, and so a test can substitute a trivial implementation without weakening the real
 * one.
 */
public interface ApiKeyHasher {

    /** Deterministic: the same key always yields the same digest, or lookup would be impossible. */
    String hash(String presentedKey);
}

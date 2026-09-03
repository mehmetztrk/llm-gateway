package io.github.mehmetztrk.llmgateway.domain.support;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identifiers for things that must be unique but need not be unguessable.
 *
 * <p><b>why not {@link UUID#randomUUID()}.</b> It draws from {@link java.security.SecureRandom},
 * which on Linux reads {@code /dev/random} through a {@code FileInputStream} — a blocking file
 * read. On a warm developer machine the entropy pool hides it; on a fresh CI runner it does not.
 * BlockHound caught exactly this on the request path, in the usage ledger and in the Ollama
 * streaming adapter, after the code had passed locally for a week.
 *
 * <p>A ledger row id and a completion id are database keys and correlation handles. Nothing about
 * them is a secret, so paying for cryptographic randomness buys nothing and costs a blocking call
 * inside a reactive pipeline.
 *
 * <p><b>Where this must NOT be used:</b> anything an attacker would benefit from guessing. API keys
 * come from {@code SecureRandom} in {@code ApiKeyGenerator} and always will — that call happens on
 * the admin path, on a blocking scheduler, where blocking is expected and paid for.
 */
public final class Ids {

    private Ids() {}

    /**
     * A version-4-shaped UUID from a non-blocking source.
     *
     * <p>{@link ThreadLocalRandom} is seeded per thread and never touches the entropy pool. The
     * version and variant bits are set so the value is still a well-formed UUIDv4 to anything that
     * inspects it — a database, a log parser, a tracing backend.
     */
    public static UUID fast() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long most = random.nextLong();
        long least = random.nextLong();

        most = (most & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L; // version 4
        least = (least & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant

        return new UUID(most, least);
    }
}

package io.github.mehmetztrk.llmgateway.adapter.out.security;

import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 with a server-side pepper.
 *
 * <p><b>why not Argon2id / bcrypt.</b> Those exist to make guessing <em>low-entropy human
 * passwords</em> expensive, and they buy that with deliberate slowness. An API key here is 256 bits
 * from a CSPRNG: there is nothing to guess, so the slowness buys nothing and costs plenty.
 *
 * <ul>
 *   <li>Authentication runs on every request. Argon2id at sane parameters is tens to hundreds of
 *       milliseconds — on its own more than ten times the entire p99 overhead budget for this
 *       gateway.
 *   <li>It hands anyone an amplification attack: unauthenticated requests with random keys would
 *       each cost the server a full Argon2 computation. A cache in front does not help, because
 *       misses are exactly what the attacker generates.
 * </ul>
 *
 * <p><b>why HMAC and not a bare SHA-256.</b> A bare digest of a stolen table can be attacked with
 * precomputed rainbow tables if keys ever turn out to be less random than intended, and it lets an
 * attacker who obtains the database verify a guessed key offline. The pepper — held in
 * configuration, never in the database — means a database dump alone is not enough.
 *
 * <p>This is the same reasoning GitHub and Stripe apply to their tokens. See ADR-0009.
 */
public class HmacApiKeyHasher implements ApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec pepper;

    public HmacApiKeyHasher(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("gateway.security.key-pepper must be set");
        }
        this.pepper = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String hash(String presentedKey) {
        try {
            // Mac is not thread-safe, so a fresh instance per call. It is cheap — microseconds —
            // which is the entire point of choosing this over a password hash.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepper);
            return HexFormat.of().formatHex(mac.doFinal(presentedKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

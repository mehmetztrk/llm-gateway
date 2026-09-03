package io.github.mehmetztrk.llmgateway.domain.cache;

import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Identity of a cacheable request.
 *
 * <p><b>The tenant is part of the key, not a filter applied afterwards.</b> That is the difference
 * between a cache that is tenant-isolated by construction and one that is isolated as long as
 * nobody forgets a {@code WHERE} clause. Two tenants sending byte-identical prompts get different
 * keys and cannot see each other's entries, and there is no code path where that could be skipped.
 *
 * <p>The model is in the key too: a tenant asking for a large model must not be served a small
 * model's answer, because that is a different product at a different price.
 *
 * <p>Sampling parameters are included because they change the answer. Caching across temperatures
 * would return a deterministic answer to someone who explicitly asked for a varied one.
 */
public record CacheKey(String value) {

    public CacheKey {
        Objects.requireNonNull(value, "value");
    }

    public static CacheKey of(TenantId tenant, ChatRequest request) {
        MessageDigest digest = sha256();
        // Length-prefixed fields, so that two different message splits cannot hash the same. Plain
        // concatenation would make ["ab","c"] and ["a","bc"] collide, and a cache collision across
        // prompts is a wrong answer served with confidence.
        update(digest, tenant.value().toString());
        update(digest, request.model());
        update(digest, String.valueOf(request.maxTokens()));
        update(digest, String.valueOf(request.temperature()));
        for (ChatMessage message : request.messages()) {
            update(digest, message.role().wireValue());
            update(digest, message.content());
        }
        return new CacheKey(HexFormat.of().formatHex(digest.digest()));
    }

    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '|');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

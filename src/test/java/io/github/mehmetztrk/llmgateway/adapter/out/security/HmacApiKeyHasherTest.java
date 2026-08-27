package io.github.mehmetztrk.llmgateway.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HmacApiKeyHasherTest {

    private final HmacApiKeyHasher hasher = new HmacApiKeyHasher("test-pepper");

    @Test
    @DisplayName("hashing is deterministic, or lookup by digest would be impossible")
    void isDeterministic() {
        assertThat(hasher.hash("llmgw_abc")).isEqualTo(hasher.hash("llmgw_abc"));
    }

    @Test
    @DisplayName("different keys produce different digests")
    void differentKeysDiffer() {
        assertThat(hasher.hash("llmgw_a")).isNotEqualTo(hasher.hash("llmgw_b"));
    }

    @Test
    @DisplayName("the digest never contains the key")
    void digestDoesNotLeakTheKey() {
        String key = "llmgw_supersecretvalue";
        assertThat(hasher.hash(key)).doesNotContain(key);
    }

    @Test
    @DisplayName("changing the pepper invalidates every existing digest")
    void pepperChangesEverything() {
        // This is the emergency lever: rotating the pepper revokes all keys at once, without
        // touching the database.
        assertThat(new HmacApiKeyHasher("pepper-one").hash("llmgw_k"))
                .isNotEqualTo(new HmacApiKeyHasher("pepper-two").hash("llmgw_k"));
    }

    @Test
    @DisplayName("produces a fixed-length hex SHA-256 digest")
    void producesHexSha256() {
        assertThat(hasher.hash("anything")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("refuses to start without a pepper rather than silently using none")
    void requiresAPepper() {
        assertThatThrownBy(() -> new HmacApiKeyHasher("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key-pepper");
    }

    @Test
    @DisplayName("is fast enough to run on every request")
    void isFastEnoughForTheHotPath() {
        // The whole reason for choosing HMAC over Argon2. Not a benchmark — a guard against
        // someone later swapping in a deliberately slow hash without noticing the consequence.
        // A password hash at sane parameters would blow this budget by two orders of magnitude.
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            hasher.hash("llmgw_key_number_" + i);
        }
        long millisFor10k = (System.nanoTime() - start) / 1_000_000;

        assertThat(millisFor10k)
                .as("10k hashes took %dms; an API-key hash must stay far below a password hash", millisFor10k)
                .isLessThan(1_000);
    }
}

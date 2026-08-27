package io.github.mehmetztrk.llmgateway.adapter.out.security;

import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyFactory;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints API keys of the form {@code llmgw_<43 base64url chars>}, carrying 256 bits from {@link
 * SecureRandom}.
 *
 * <p>why a fixed, recognisable prefix: secret scanners — GitHub's, and most commercial ones — match
 * on exactly this kind of marker. A key that looks like generic base64 sails through unnoticed when
 * someone commits it; one that starts with {@code llmgw_} gets flagged.
 */
public class ApiKeyGenerator implements ApiKeyFactory {

    public static final String KEY_PREFIX = "llmgw_";

    /** Enough to identify a key in a list; far too little to reconstruct one. */
    private static final int DISPLAY_PREFIX_LENGTH = KEY_PREFIX.length() + 6;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    @Override
    public String displayPrefix(String key) {
        return key.length() <= DISPLAY_PREFIX_LENGTH ? key : key.substring(0, DISPLAY_PREFIX_LENGTH) + "...";
    }
}

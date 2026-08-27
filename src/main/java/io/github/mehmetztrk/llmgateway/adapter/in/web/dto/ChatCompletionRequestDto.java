package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The OpenAI {@code /v1/chat/completions} request body.
 *
 * <p>why {@code ignoreUnknown = true}: OpenAI SDKs send a long tail of parameters (tools, seed,
 * response_format, user, ...). Rejecting a request because of a parameter the gateway does not act
 * on would break the promise that "any OpenAI SDK works by changing base_url". Unrecognised
 * parameters are dropped rather than forwarded — forwarding something we do not understand would be
 * worse, because the tenant would believe it took effect.
 *
 * @param stream nullable on purpose: {@code null} and {@code false} both mean non-streaming, and we
 *     need to tell "absent" from "explicitly false" when reporting errors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequestDto(
        @NotBlank(message = "model is required") String model,

        @NotEmpty(message = "messages must not be empty") @Valid List<MessageDto> messages,

        Boolean stream,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature) {}

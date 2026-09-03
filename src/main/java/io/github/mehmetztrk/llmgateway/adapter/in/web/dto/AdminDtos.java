package io.github.mehmetztrk.llmgateway.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.mehmetztrk.llmgateway.application.port.in.AdminUseCase;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKey;
import io.github.mehmetztrk.llmgateway.domain.tenant.Tenant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wire types for {@code /admin/**}.
 *
 * <p>This is the gateway's own API rather than an OpenAI-compatible one, so the shapes are chosen
 * for clarity instead of compatibility.
 */
public final class AdminDtos {

    private AdminDtos() {}

    public record CreateTenantRequest(
            @NotBlank(message = "name is required") String name,
            @NotNull(message = "allowedModels is required") Set<String> allowedModels) {}

    public record SetAllowedModelsRequest(
            @NotNull(message = "allowedModels is required") Set<String> allowedModels) {}

    public record IssueKeyRequest(String role, String label) {}

    /**
     * @param monthlyTokenBudget null means no monthly ceiling
     * @param quotaSoftThreshold fraction of the budget at which the warning header appears
     */
    public record SetLimitsRequest(
            @Positive(message = "requestsPerMinute must be positive") int requestsPerMinute,

            @Positive(message = "tokensPerMinute must be positive") long tokensPerMinute,

            Long monthlyTokenBudget,
            Double quotaSoftThreshold) {}

    public record TenantResponse(UUID id, String name, boolean active, Set<String> allowedModels, Instant createdAt) {

        public static TenantResponse from(Tenant tenant) {
            return new TenantResponse(
                    tenant.id().value(),
                    tenant.name(),
                    tenant.active(),
                    tenant.allowedModels().models(),
                    tenant.createdAt());
        }
    }

    /** Note the absence of anything resembling the key itself. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiKeyResponse(
            UUID id, UUID tenantId, String keyPrefix, String role, String label, Instant createdAt, Instant revokedAt) {

        public static ApiKeyResponse from(ApiKey key) {
            return new ApiKeyResponse(
                    key.id(),
                    key.tenantId().value(),
                    key.keyPrefix(),
                    key.role().name(),
                    key.label(),
                    key.createdAt(),
                    key.revokedAt());
        }
    }

    /**
     * The only response that ever carries a usable key.
     *
     * @param key the plaintext, returned once and unrecoverable afterwards — the {@code warning}
     *     field exists so that is obvious to whoever is reading the response, not only to whoever
     *     read the docs
     */
    public record IssuedKeyResponse(String key, ApiKeyResponse metadata, String warning) {

        private static final String WARNING = "Store this key now. It is not retrievable later.";

        public static IssuedKeyResponse from(AdminUseCase.IssuedApiKey issued) {
            return new IssuedKeyResponse(issued.plaintext(), ApiKeyResponse.from(issued.record()), WARNING);
        }
    }

    public record TenantListResponse(List<TenantResponse> tenants) {}

    /**
     * Operational view of routing. Exposed because "which provider is the gateway avoiding right
     * now, and why" is the first question during an incident, and grepping logs for it is not an
     * answer.
     */
    public record ProviderStatusResponse(List<ProviderStatus> providers) {}

    public record ProviderStatus(String id, String health, Set<String> models) {}

    public record ApiKeyListResponse(List<ApiKeyResponse> keys) {}
}

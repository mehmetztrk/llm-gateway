package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.AdminDtos;
import io.github.mehmetztrk.llmgateway.application.port.in.AdminUseCase;
import io.github.mehmetztrk.llmgateway.application.service.ProviderRegistry;
import io.github.mehmetztrk.llmgateway.application.service.RoutingService;
import io.github.mehmetztrk.llmgateway.domain.limits.QuotaPolicy;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitPolicy;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderHealth;
import io.github.mehmetztrk.llmgateway.domain.tenant.ApiKeyRole;
import io.github.mehmetztrk.llmgateway.domain.tenant.ModelAllowList;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Tenant and key administration. Requires an {@code ADMIN} key; enforced in {@code SecurityConfig},
 * not here.
 *
 * <p>Every method wraps a blocking call in {@code Mono.fromCallable(...).subscribeOn(...)}. That is
 * verbose, and deliberately so: the hop is the thing that keeps JDBC off the event loop, and hiding
 * it behind a helper would make it easy to add a method that forgets it. See ADR-0006.
 */
@RestController
@RequestMapping("/admin")
class AdminController {

    private final AdminUseCase admin;
    private final Scheduler blockingScheduler;
    private final ProviderRegistry providerRegistry;
    private final RoutingService routing;

    AdminController(
            AdminUseCase admin,
            Scheduler blockingScheduler,
            ProviderRegistry providerRegistry,
            RoutingService routing) {
        this.admin = admin;
        this.blockingScheduler = blockingScheduler;
        this.providerRegistry = providerRegistry;
        this.routing = routing;
    }

    private <T> Mono<T> offloaded(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(blockingScheduler);
    }

    /**
     * why this is not an actuator health indicator: actuator health decides whether the process
     * should be restarted or taken out of a load balancer. A provider being down is neither — the
     * gateway is working exactly as designed, by routing around it. Conflating the two turns a
     * successful failover into a rolling restart.
     */
    @GetMapping("/providers")
    Mono<AdminDtos.ProviderStatusResponse> providers() {
        return Mono.fromSupplier(() -> new AdminDtos.ProviderStatusResponse(providerRegistry.all().stream()
                .map(provider -> new AdminDtos.ProviderStatus(
                        provider.id().value(),
                        routing.healthSnapshot()
                                .getOrDefault(provider.id(), ProviderHealth.UNKNOWN)
                                .name(),
                        provider.supportedModels()))
                .toList()));
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<AdminDtos.TenantResponse> createTenant(@Valid @RequestBody AdminDtos.CreateTenantRequest request) {
        return offloaded(() -> admin.createTenant(request.name(), new ModelAllowList(request.allowedModels())))
                .map(AdminDtos.TenantResponse::from);
    }

    @GetMapping("/tenants")
    Mono<AdminDtos.TenantListResponse> listTenants() {
        return offloaded(admin::listTenants)
                .map(tenants -> new AdminDtos.TenantListResponse(
                        tenants.stream().map(AdminDtos.TenantResponse::from).toList()));
    }

    @GetMapping("/tenants/{tenantId}")
    Mono<AdminDtos.TenantResponse> getTenant(@PathVariable UUID tenantId) {
        return offloaded(() -> admin.getTenant(TenantId.of(tenantId))).map(AdminDtos.TenantResponse::from);
    }

    @PutMapping("/tenants/{tenantId}/models")
    Mono<AdminDtos.TenantResponse> setAllowedModels(
            @PathVariable UUID tenantId, @Valid @RequestBody AdminDtos.SetAllowedModelsRequest request) {
        return offloaded(() -> {
                    TenantId id = TenantId.of(tenantId);
                    admin.setAllowedModels(id, new ModelAllowList(request.allowedModels()));
                    return admin.getTenant(id);
                })
                .map(AdminDtos.TenantResponse::from);
    }

    @PutMapping("/tenants/{tenantId}/limits")
    Mono<AdminDtos.TenantResponse> setLimits(
            @PathVariable UUID tenantId, @Valid @RequestBody AdminDtos.SetLimitsRequest request) {
        return offloaded(() -> {
                    TenantId id = TenantId.of(tenantId);
                    admin.setLimits(
                            id,
                            new RateLimitPolicy(request.requestsPerMinute(), request.tokensPerMinute()),
                            new QuotaPolicy(
                                    request.monthlyTokenBudget(),
                                    request.quotaSoftThreshold() == null ? 0.8 : request.quotaSoftThreshold()));
                    return admin.getTenant(id);
                })
                .map(AdminDtos.TenantResponse::from);
    }

    @PostMapping("/tenants/{tenantId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<AdminDtos.IssuedKeyResponse> issueKey(
            @PathVariable UUID tenantId, @RequestBody(required = false) AdminDtos.IssueKeyRequest request) {
        ApiKeyRole role = request == null || request.role() == null
                ? ApiKeyRole.TENANT
                : ApiKeyRole.valueOf(request.role().toUpperCase(java.util.Locale.ROOT));
        String label = request == null ? null : request.label();

        return offloaded(() -> admin.issueKey(TenantId.of(tenantId), role, label))
                .map(AdminDtos.IssuedKeyResponse::from);
    }

    @GetMapping("/tenants/{tenantId}/keys")
    Mono<AdminDtos.ApiKeyListResponse> listKeys(@PathVariable UUID tenantId) {
        return offloaded(() -> admin.listKeys(TenantId.of(tenantId)))
                .map(keys -> new AdminDtos.ApiKeyListResponse(
                        keys.stream().map(AdminDtos.ApiKeyResponse::from).toList()));
    }

    /**
     * why 204 for an already-revoked key rather than 404: revocation is the kind of thing an
     * operator does under pressure, sometimes twice. Idempotent success is the safer contract.
     */
    @DeleteMapping("/keys/{keyId}")
    Mono<ResponseEntity<Void>> revokeKey(@PathVariable UUID keyId) {
        return offloaded(() -> admin.revokeKey(keyId))
                .map(revoked -> ResponseEntity.noContent().build());
    }
}

package io.github.mehmetztrk.llmgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ErrorResponseDto;
import io.github.mehmetztrk.llmgateway.adapter.in.web.security.ApiKeyAuthenticationFilter;
import io.github.mehmetztrk.llmgateway.application.port.out.ApiKeyHasher;
import io.github.mehmetztrk.llmgateway.application.port.out.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * API-key security for a stateless, machine-to-machine API.
 *
 * <p>Several Spring Security defaults are switched off, each for a specific reason rather than to
 * make warnings go away:
 *
 * <ul>
 *   <li><b>CSRF</b> — a cross-site request cannot attach an {@code Authorization} header, and there
 *       is no cookie or session to ride on. CSRF protection defends browser-session auth, which
 *       this is not.
 *   <li><b>Session persistence</b> — {@link NoOpServerSecurityContextRepository} keeps every
 *       request independently authenticated. A gateway that held per-client session state could not
 *       be scaled by adding replicas behind a round-robin.
 *   <li><b>HTTP Basic and form login</b> — unused entry points are attack surface and confusing
 *       404-versus-401 behaviour.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
            TenantRepository tenants, ApiKeyHasher hasher, Scheduler blockingScheduler) {
        return new ApiKeyAuthenticationFilter(tenants, hasher, blockingScheduler);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter, ObjectMapper objectMapper) {

        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .addFilterAt(
                        apiKeyFilter,
                        org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchange -> exchange
                        // Liveness and readiness must answer before the database is reachable,
                        // or an orchestrator could never tell "starting up" from "misconfigured".
                        .pathMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // The demo console is a static page that carries no data of its own; the
                        // calls it makes are authenticated like any other.
                        .pathMatchers("/", "/index.html", "/favicon.ico")
                        .permitAll()
                        .pathMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .pathMatchers("/v1/**")
                        .hasAnyRole("TENANT", "ADMIN")
                        .anyExchange()
                        .denyAll())
                .exceptionHandling(spec -> spec.authenticationEntryPoint(entryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .build();
    }

    /**
     * why write the body by hand instead of letting Spring Security answer: its default 401 is an
     * empty body with a {@code WWW-Authenticate} header. An OpenAI SDK parsing that gets an opaque
     * failure. Emitting the same error envelope as every other endpoint means the SDK raises
     * {@code AuthenticationError} with a readable message.
     */
    private ServerAuthenticationEntryPoint entryPoint(ObjectMapper objectMapper) {
        return (exchange, exception) -> write(
                exchange,
                objectMapper,
                HttpStatus.UNAUTHORIZED,
                ErrorResponseDto.of(
                        "Incorrect API key provided, or no key provided. "
                                + "Send it as 'Authorization: Bearer <key>'.",
                        "invalid_request_error",
                        "invalid_api_key"));
    }

    private ServerAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (exchange, exception) -> write(
                exchange,
                objectMapper,
                HttpStatus.FORBIDDEN,
                ErrorResponseDto.of(
                        "This API key does not have permission to perform that operation.",
                        "invalid_request_error",
                        "insufficient_permissions"));
    }

    private Mono<Void> write(
            ServerWebExchange exchange, ObjectMapper objectMapper, HttpStatus status, ErrorResponseDto body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            DataBuffer buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(body));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }
}

package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ErrorResponseDto;
import io.github.mehmetztrk.llmgateway.domain.error.FeatureNotSupported;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.LimiterUnavailable;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.error.NoProviderAvailable;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.error.QuotaExceeded;
import io.github.mehmetztrk.llmgateway.domain.error.RateLimitExceeded;
import io.github.mehmetztrk.llmgateway.domain.error.TenantNotFound;
import io.github.mehmetztrk.llmgateway.domain.limits.RateLimitSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Maps failures onto OpenAI's error envelope.
 *
 * <p>why a switch over a sealed hierarchy rather than one {@code @ExceptionHandler} per type: the
 * compiler checks exhaustiveness. Adding a new {@link GatewayException} subtype without deciding
 * its status code becomes a build failure instead of a silent 500 discovered in production.
 */
@RestControllerAdvice
class GatewayErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<ErrorResponseDto> handleGatewayException(GatewayException exception) {
        return switch (exception) {
            case ModelNotFound e ->
                respond(HttpStatus.NOT_FOUND, e.getMessage(), "invalid_request_error", "model_not_found", e);
            case ModelNotAllowed e ->
                respond(HttpStatus.FORBIDDEN, e.getMessage(), "invalid_request_error", "model_not_allowed", e);
            case FeatureNotSupported e ->
                respond(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error", "unsupported_parameter", e);
            case ProviderCallFailed e ->
                respond(
                        HttpStatus.BAD_GATEWAY,
                        "The upstream provider failed to complete the request.",
                        "api_error",
                        "provider_error",
                        e);
            case TenantNotFound e ->
                respond(HttpStatus.NOT_FOUND, e.getMessage(), "invalid_request_error", "tenant_not_found", e);
            case NoProviderAvailable e ->
                respond(
                        // 503 rather than 502: 502 says the thing behind me is broken, 503 says I
                        // have nothing left to try. The distinction matters to whoever is paged.
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "No provider is currently able to serve this model.",
                        "api_error",
                        "no_provider_available",
                        e);
            case RateLimitExceeded e -> rateLimited(e);
            case QuotaExceeded e ->
                respond(
                        // 429 rather than 402: OpenAI uses 429 with insufficient_quota, and SDK
                        // retry logic already understands it. The code, not the status, is what
                        // tells a client that waiting will not help.
                        HttpStatus.TOO_MANY_REQUESTS,
                        "You have exceeded your monthly token budget. "
                                + "This does not reset until the next billing period.",
                        "insufficient_quota",
                        "insufficient_quota",
                        e);
            case LimiterUnavailable e ->
                respond(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Rate limiting is temporarily unavailable, so the request was refused.",
                        "api_error",
                        "rate_limiter_unavailable",
                        e);
            // No default branch: GatewayException is abstract and sealed, so this switch is
            // exhaustive and a new subtype will not compile until it is handled here.
        };
    }

    /** Bean-validation failures on the request body. */
    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ErrorResponseDto> handleValidation(WebExchangeBindException exception) {
        String field = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField())
                .orElse(null);
        String message = exception.getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request body.");
        log.debug("rejected malformed request: {}", message);
        return ResponseEntity.badRequest()
                .body(ErrorResponseDto.of(message, "invalid_request_error", field, "invalid_request"));
    }

    /** Unparseable JSON, wrong content type, and similar transport-level input problems. */
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ErrorResponseDto> handleBadInput(ServerWebInputException exception) {
        log.debug("rejected unreadable request: {}", exception.getReason());
        return ResponseEntity.badRequest()
                .body(ErrorResponseDto.of(
                        "Request body could not be read as JSON.", "invalid_request_error", "invalid_request"));
    }

    /**
     * Anything unmapped. The client is told nothing beyond "internal error" on purpose: upstream
     * exception messages routinely contain URLs, hostnames and occasionally fragments of the
     * prompt, none of which belong in a response body a tenant can read.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponseDto> handleUnexpected(Exception exception) {
        // Anything Spring already assigned a status to keeps it. Without this, a plain 404 for an
        // unmapped path arrives here and leaves as a 500 — which is both wrong and alarming, since
        // it turns "you asked for something that does not exist" into "the gateway is broken".
        // Found by a metrics test that expected a 200 and got a 500 that was really a 404.
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            log.debug("{} for {}", status, exception.getMessage());
            return ResponseEntity.status(status)
                    .body(ErrorResponseDto.of(
                            status.is4xxClientError() ? status.getReasonPhrase() : "Internal error.",
                            status.is4xxClientError() ? "invalid_request_error" : "api_error",
                            status.is4xxClientError() ? "not_found" : "internal_error"));
        }

        log.error("unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponseDto.of("Internal error.", "api_error", "internal_error"));
    }

    /**
     * A 429 without {@code Retry-After} is an invitation to hammer, so the refusal carries the
     * exact wait the bucket calculated. The rate-limit headers go out too: the client should be
     * able to see how far over it went, not just that it did.
     */
    private ResponseEntity<ErrorResponseDto> rateLimited(RateLimitExceeded exception) {
        RateLimitSnapshot snapshot = exception.snapshot();
        log.debug("rate_limit_exceeded on {} -> retry after {}s", exception.scope(), snapshot.retryAfterSeconds());

        boolean tokenBucket = exception.scope() == RateLimitExceeded.Scope.TENANT_TOKENS
                || exception.scope() == RateLimitExceeded.Scope.KEY_TOKENS;

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(snapshot.retryAfterSeconds()))
                .header(
                        tokenBucket ? RateLimitHeaders.LIMIT_TOKENS : RateLimitHeaders.LIMIT_REQUESTS,
                        Long.toString(snapshot.limit()))
                .header(
                        tokenBucket ? RateLimitHeaders.REMAINING_TOKENS : RateLimitHeaders.REMAINING_REQUESTS,
                        Long.toString(snapshot.remaining()))
                .header(
                        tokenBucket ? RateLimitHeaders.RESET_TOKENS : RateLimitHeaders.RESET_REQUESTS,
                        Long.toString(snapshot.retryAfterSeconds()))
                .body(ErrorResponseDto.of(
                        "Rate limit reached. Please retry after " + snapshot.retryAfterSeconds() + " seconds.",
                        "rate_limit_error",
                        "rate_limit_exceeded"));
    }

    private ResponseEntity<ErrorResponseDto> respond(
            HttpStatus status, String message, String type, String code, GatewayException exception) {
        // 5xx means we are broken and someone must look; 4xx means the caller is, and logging it
        // at anything above debug turns a misbehaving client into log spam for the operator.
        if (status.is5xxServerError()) {
            log.error("{} -> {}", code, exception.getMessage(), exception);
        } else {
            log.debug("{} -> {}", code, exception.getMessage());
        }
        return ResponseEntity.status(status).body(ErrorResponseDto.of(message, type, code));
    }
}

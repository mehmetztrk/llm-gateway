package io.github.mehmetztrk.llmgateway.adapter.in.web;

import io.github.mehmetztrk.llmgateway.adapter.in.web.dto.ErrorResponseDto;
import io.github.mehmetztrk.llmgateway.domain.error.FeatureNotSupported;
import io.github.mehmetztrk.llmgateway.domain.error.GatewayException;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotAllowed;
import io.github.mehmetztrk.llmgateway.domain.error.ModelNotFound;
import io.github.mehmetztrk.llmgateway.domain.error.ProviderCallFailed;
import io.github.mehmetztrk.llmgateway.domain.error.TenantNotFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        log.error("unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponseDto.of("Internal error.", "api_error", "internal_error"));
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

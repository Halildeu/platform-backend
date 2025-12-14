package com.example.apigateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VaultFailfastFallbackHandler implements ErrorWebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(VaultFailfastFallbackHandler.class);
    private static final String OUTAGE_CODE = "VAULT_UNAVAILABLE";
    private static final String MESSAGE = "Kimlik altyapısı devrede değil. Bakım tamamlanınca otomatik denenecek.";

    private final ObjectMapper objectMapper;

    public VaultFailfastFallbackHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (!shouldHandle(unwrapped) || exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        log.warn("Vault fail-fast fallback devreye alındı: {}", unwrapped.getMessage());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Duration.ofMinutes(1).getSeconds()));
        response.getHeaders().set("X-Serban-Outage-Code", OUTAGE_CODE);

        Map<String, Object> payload = buildPayload(exchange);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private boolean shouldHandle(Throwable throwable) {
        if (throwable instanceof NotFoundException) {
            return true;
        }
        if (throwable instanceof WebClientRequestException) {
            return true;
        }
        if (throwable instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            return status != null && (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT);
        }
        Throwable root = Exceptions.unwrap(throwable);
        return root instanceof ConnectException
                || root instanceof NoRouteToHostException
                || root instanceof SocketTimeoutException
                || root instanceof TimeoutException;
    }

    private Map<String, Object> buildPayload(ServerWebExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "vault_unavailable");
        body.put("message", MESSAGE);
        body.put("fieldErrors", Collections.emptyList());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", resolveTraceId(exchange));
        meta.put("outageCode", OUTAGE_CODE);
        body.put("meta", meta);
        return body;
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        header = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return exchange.getRequest().getId();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

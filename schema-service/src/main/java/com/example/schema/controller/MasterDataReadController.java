package com.example.schema.controller;

import com.example.schema.dto.MasterDataItemDto;
import com.example.schema.service.MasterDataReadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Codex 019dda1c iter-29: internal master-data read endpoint.
 * Consumed only by permission-service's MasterDataController (BFF for
 * the role drawer / user drawer scope pickers); not exposed to mfe-*
 * frontends directly.
 *
 * <p>Auth model: an {@code X-Internal-Api-Key} header guard, verified
 * against {@code schema.master-data.internal-api-key}. Permission-service
 * already runs the user authorization check ({@code @RequireModule
 * ACCESS:can_view}), so this provider endpoint only proves
 * "the request comes from the trusted backend, not the public gateway".
 *
 * <p>This is intentionally NOT registered with the gateway as a public
 * route. Cross-service calls inside the cluster reach it via the
 * service DNS ({@code http://schema-service:8096}); the gateway should
 * not surface {@code /api/v1/schema/master-data/*} on the public host.
 */
@RestController
@RequestMapping("/api/v1/schema/master-data")
public class MasterDataReadController {

    private static final Logger log = LoggerFactory.getLogger(MasterDataReadController.class);
    private static final String INTERNAL_HEADER = "X-Internal-Api-Key";

    private final MasterDataReadService service;
    private final String expectedKey;

    public MasterDataReadController(
            MasterDataReadService service,
            @Value("${schema.master-data.internal-api-key:}") String expectedKey) {
        this.service = service;
        this.expectedKey = expectedKey;
    }

    @GetMapping("/{kind}")
    public ResponseEntity<List<MasterDataItemDto>> list(
            @PathVariable String kind,
            @RequestHeader(value = INTERNAL_HEADER, required = false) String providedKey) {

        // Internal key guard. Empty configured key (test/dev profile) means
        // open access; production deployments MUST set the key via Vault.
        if (expectedKey != null && !expectedKey.isBlank()
                && (providedKey == null || !expectedKey.equals(providedKey))) {
            log.warn("MasterData internal endpoint reject — missing or wrong {} header", INTERNAL_HEADER);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing or invalid internal API key");
        }

        try {
            List<MasterDataItemDto> items = service.list(kind);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS))
                    .body(items);
        } catch (IllegalArgumentException badKind) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, badKind.getMessage());
        }
    }
}

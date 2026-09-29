package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.DisplayPolicyAssetService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #508 Endpoint Display Policy — agent-side wallpaper download
 * (platform-backend#1203).
 *
 * <p>{@code GET /api/v1/agent/display-policy-assets/{sha256}} sits under
 * {@code /api/v1/agent/**}, so the existing device-credential filter
 * authenticates it before this controller runs; the principal is the calling
 * device. {@link DisplayPolicyAssetService#loadForDevice} then serves the image
 * only if that device's currently approved policy names this hash.
 *
 * <p>The hash is echoed in {@code X-Content-Sha256} as a convenience, but the
 * agent must recompute it over the bytes it received — the header is not a
 * substitute for verification. {@code no-store}: the image belongs to one
 * policy decision and must not be cached by anything in between.
 */
@RestController
@RequestMapping("/api/v1/agent/display-policy-assets")
@ConditionalOnPrimaryEndpointPlane
public class AgentDisplayPolicyAssetController {

    private final DisplayPolicyAssetService service;

    public AgentDisplayPolicyAssetController(DisplayPolicyAssetService service) {
        this.service = service;
    }

    @GetMapping("/{sha256}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal DeviceCredentialResult principal,
                                           @PathVariable String sha256) {
        EndpointDisplayPolicyAsset asset = service.loadForDevice(
                principal == null ? null : principal.deviceId(), sha256);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(asset.getSizeBytes())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Sha256", asset.getSha256())
                .body(asset.getContent());
    }
}

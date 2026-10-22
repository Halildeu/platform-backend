package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyAssetResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.DisplayPolicyAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * #508 Endpoint Display Policy — managed wallpaper upload (platform-backend#1203).
 *
 * <p>{@code POST /api/v1/admin/display-policy-assets} (multipart, field
 * {@code file}) stores a wallpaper image and returns the {@code assetRef} a
 * policy uses to point at it. Uploading does not touch any device: the image
 * only reaches an endpoint through a display-policy ENFORCE proposal, which
 * stays maker-checker. Same MANAGER relation as the policy endpoints, so anyone
 * who may propose a wallpaper may also supply one and nobody else.
 */
@RestController
@RequestMapping("/api/v1/admin/display-policy-assets")
public class AdminDisplayPolicyAssetController {

    private final DisplayPolicyAssetService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminDisplayPolicyAssetController(DisplayPolicyAssetService service,
                                             TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminDisplayPolicyAssetResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A wallpaper image file is required.");
        }
        // Refuse an oversized part before reading it into memory; the service
        // re-checks the actual byte count as the authoritative gate.
        if (file.getSize() > DisplayPolicyAssetService.MAX_ASSET_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Wallpaper image exceeds " + DisplayPolicyAssetService.MAX_ASSET_BYTES + " bytes.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wallpaper image could not be read.");
        }
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        AdminDisplayPolicyAssetResponse response = service.upload(context, content, file.getContentType());
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }
}

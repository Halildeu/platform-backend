package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;

import java.time.Instant;

/**
 * #508 managed wallpaper asset, as returned to the admin after upload
 * (platform-backend#1203).
 *
 * <p>{@code assetRef}, {@code assetSha256} and {@code contentType} are exactly
 * the three values the admin copies into the wallpaper section of the
 * display-policy PUT; returning them ready-made means the web view never has to
 * construct the managed-ref scheme itself. {@code created} is false when the same
 * bytes were already stored, so a repeated upload is visibly idempotent.
 */
public record AdminDisplayPolicyAssetResponse(
        String assetRef,
        String assetSha256,
        String contentType,
        int sizeBytes,
        boolean created,
        Instant createdAt) {

    public static AdminDisplayPolicyAssetResponse of(EndpointDisplayPolicyAsset asset, boolean created) {
        return new AdminDisplayPolicyAssetResponse(
                asset.managedRef(),
                asset.getSha256(),
                asset.getContentType(),
                asset.getSizeBytes(),
                created,
                asset.getCreatedAt());
    }
}

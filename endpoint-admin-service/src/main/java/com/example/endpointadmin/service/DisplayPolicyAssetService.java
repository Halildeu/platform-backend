package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyAssetResponse;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyAssetRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * #508 Endpoint Display Policy — managed wallpaper assets (platform-backend#1203).
 *
 * <p>Two jobs, both deliberately narrow:
 * <ul>
 *   <li><b>Upload</b> (admin): store an image once, keyed by a hash computed
 *       here. The declared type must match the file's magic bytes, so a renamed
 *       executable cannot be stored as {@code image/png}.</li>
 *   <li><b>Download</b> (agent): hand an image only to a device whose
 *       <em>currently approved</em> policy names it. The approval listener
 *       promotes a revision to the current row synchronously inside the approval
 *       transaction, and the agent can only claim the command after that commits,
 *       so "the current row references this hash" is exactly the right boundary:
 *       a device can fetch the image it has been told to render and nothing else.
 *       Every refusal is a 404, so one device cannot probe which images exist for
 *       another.</li>
 * </ul>
 */
@Service
public class DisplayPolicyAssetService {

    private static final Logger LOG = LoggerFactory.getLogger(DisplayPolicyAssetService.class);

    /** 10 MiB — mirrors the V83 {@code ck_edpa_size} backstop. */
    public static final int MAX_ASSET_BYTES = 10 * 1024 * 1024;

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] BMP_MAGIC = {'B', 'M'};

    private final EndpointDisplayPolicyAssetRepository assetRepository;
    private final EndpointDisplayPolicyRepository policyRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final Clock clock;
    private final boolean featureEnabled;

    public DisplayPolicyAssetService(EndpointDisplayPolicyAssetRepository assetRepository,
                                     EndpointDisplayPolicyRepository policyRepository,
                                     EndpointDeviceRepository deviceRepository,
                                     Clock clock,
                                     @Value("${endpoint-admin.display-policy.enabled:false}")
                                             boolean featureEnabled) {
        this.assetRepository = assetRepository;
        this.policyRepository = policyRepository;
        this.deviceRepository = deviceRepository;
        this.clock = clock;
        this.featureEnabled = featureEnabled;
    }

    // ─────────────────────────────────────────────────────────────
    // Upload (admin)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminDisplayPolicyAssetResponse upload(AdminTenantContext context,
                                                  byte[] content,
                                                  String declaredContentType) {
        assertFeatureEnabled();
        if (content == null || content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wallpaper image is empty.");
        }
        if (content.length > MAX_ASSET_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Wallpaper image is " + content.length + " bytes; the limit is "
                            + MAX_ASSET_BYTES + ".");
        }
        String detected = detectContentType(content);
        if (detected == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Wallpaper must be a PNG, JPEG or BMP image.");
        }
        String declared = declaredContentType == null ? null
                : declaredContentType.trim().toLowerCase(Locale.ROOT);
        if (declared != null && !declared.isEmpty() && !declared.equals(detected)) {
            // The client said one thing, the bytes say another. Refuse rather than
            // silently trust either: a mismatch is how a disguised file looks.
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Declared type " + declared + " does not match the file content (" + detected + ").");
        }

        UUID tenantId = context.tenantId();
        String sha256 = sha256Hex(content);

        Optional<EndpointDisplayPolicyAsset> existing = assetRepository.findByTenantIdAndSha256(tenantId, sha256);
        if (existing.isPresent()) {
            return AdminDisplayPolicyAssetResponse.of(existing.get(), false);
        }
        String subject = context.subject() == null || context.subject().isBlank()
                ? "unknown-admin" : context.subject().trim();
        return insertOrReuse(new EndpointDisplayPolicyAsset(
                tenantId, sha256, detected, content, subject, Instant.now(clock)));
    }

    /**
     * Stores {@code asset}, or returns the row a concurrent upload of the same
     * image committed first: the unique {@code (org_id, sha256)} index lets
     * exactly one in, and both admins get the same answer.
     *
     * <p>Must run outside any transaction ({@link #upload} suspends one).
     * PostgreSQL aborts a transaction on a unique violation, so a re-read that
     * shared the failed insert's transaction could never see the winner.
     */
    private AdminDisplayPolicyAssetResponse insertOrReuse(EndpointDisplayPolicyAsset asset) {
        try {
            assetRepository.saveAndFlush(asset);
        } catch (DataIntegrityViolationException race) {
            return assetRepository.findByTenantIdAndSha256(asset.getTenantId(), asset.getSha256())
                    .map(a -> AdminDisplayPolicyAssetResponse.of(a, false))
                    .orElseThrow(() -> race);
        }
        LOG.info("display-policy asset stored sha256={} type={} bytes={} subject={}",
                asset.getSha256().substring(0, 12), asset.getContentType(), asset.getSizeBytes(),
                asset.getCreatedBySubject());
        return AdminDisplayPolicyAssetResponse.of(asset, true);
    }

    // ─────────────────────────────────────────────────────────────
    // Policy-time check (called from ENFORCE)

    /**
     * Resolves a managed {@code assetRef} for an ENFORCE proposal. The validator
     * has already pinned the ref's shape and that it agrees with
     * {@code assetSha256}; this checks the image actually exists in the tenant
     * and that the proposal's declared type is the stored one.
     */
    @Transactional(readOnly = true)
    public EndpointDisplayPolicyAsset requireManagedAsset(UUID tenantId, String sha256, String contentType) {
        EndpointDisplayPolicyAsset asset = assetRepository.findByTenantIdAndSha256(tenantId, sha256)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "wallpaper asset " + sha256 + " has not been uploaded."));
        if (contentType != null && !asset.getContentType().equalsIgnoreCase(contentType.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "wallpaper.contentType " + contentType + " does not match the uploaded asset ("
                            + asset.getContentType() + ").");
        }
        return asset;
    }

    // ─────────────────────────────────────────────────────────────
    // Download (agent)

    @Transactional(readOnly = true)
    public EndpointDisplayPolicyAsset loadForDevice(String deviceIdValue, String sha256) {
        assertFeatureEnabled();
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw notFound();
        }
        UUID deviceId;
        try {
            deviceId = UUID.fromString(deviceIdValue);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw notFound();
        }
        EndpointDevice device = deviceRepository.findById(deviceId).orElseThrow(this::notFound);
        UUID tenantId = device.getTenantId();

        EndpointDisplayPolicy current = policyRepository.findByTenantIdAndDeviceId(tenantId, deviceId)
                .orElseThrow(this::notFound);
        boolean referencesIt = current.getOperation() == DisplayPolicyOperation.ENFORCE
                && current.getClearedAt() == null
                && sha256.equals(current.getWallpaperAssetSha256())
                && (EndpointDisplayPolicyAsset.MANAGED_REF_PREFIX + sha256)
                        .equals(current.getWallpaperAssetRef());
        if (!referencesIt) {
            throw notFound();
        }
        return assetRepository.findByTenantIdAndSha256(tenantId, sha256).orElseThrow(this::notFound);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers

    static String detectContentType(byte[] content) {
        if (startsWith(content, PNG_MAGIC)) {
            return "image/png";
        }
        if (startsWith(content, JPEG_MAGIC)) {
            return "image/jpeg";
        }
        if (startsWith(content, BMP_MAGIC)) {
            return "image/bmp";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found.");
    }

    private void assertFeatureEnabled() {
        if (!featureEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "#508 endpoint display-policy surface is disabled "
                            + "(endpoint-admin.display-policy.enabled=false).");
        }
    }
}

package com.example.endpointadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyAssetResponse;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyAssetRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * platform-backend#1203 — managed wallpaper assets. The refusal paths matter
 * more than the happy path: an image must reach only the device whose approved
 * policy names it, and a file must be the type it claims to be.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisplayPolicyAssetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE = UUID.randomUUID();

    private static final byte[] PNG = withMagic(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    private static final byte[] JPEG = withMagic(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    private static final byte[] BMP = withMagic(new byte[] {'B', 'M'});
    private static final byte[] EXE = withMagic(new byte[] {'M', 'Z'});

    @Mock private EndpointDisplayPolicyAssetRepository assetRepository;
    @Mock private EndpointDisplayPolicyRepository policyRepository;
    @Mock private EndpointDeviceRepository deviceRepository;

    private final AdminTenantContext admin = new AdminTenantContext(TENANT, "maker-admin");
    private DisplayPolicyAssetService service;

    private static byte[] withMagic(byte[] magic) {
        byte[] out = Arrays.copyOf(magic, 64);
        Arrays.fill(out, magic.length, out.length, (byte) 7);
        return out;
    }

    private DisplayPolicyAssetService service(boolean enabled) {
        return new DisplayPolicyAssetService(assetRepository, policyRepository, deviceRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), enabled);
    }

    @BeforeEach
    void setUp() {
        service = service(true);
        when(assetRepository.findByTenantIdAndSha256(any(), any())).thenReturn(Optional.empty());
    }

    // ── upload ───────────────────────────────────────────────────────────────

    @Test
    void uploadStoresAPngUnderItsServerComputedHash() {
        AdminDisplayPolicyAssetResponse res = service.upload(admin, PNG, "image/png");

        String expected = DisplayPolicyAssetService.sha256Hex(PNG);
        assertThat(res.assetSha256()).isEqualTo(expected);
        assertThat(res.assetRef()).isEqualTo("asset:sha256:" + expected);
        assertThat(res.contentType()).isEqualTo("image/png");
        assertThat(res.sizeBytes()).isEqualTo(PNG.length);
        assertThat(res.created()).isTrue();
        verify(assetRepository).saveAndFlush(any(EndpointDisplayPolicyAsset.class));
    }

    @Test
    void theTypeComesFromTheBytesNotTheFileName() {
        assertThat(service.upload(admin, JPEG, null).contentType()).isEqualTo("image/jpeg");
        assertThat(service.upload(admin, BMP, "").contentType()).isEqualTo("image/bmp");
    }

    @Test
    void aDisguisedFileIsRefused() {
        // A renamed executable announced as a PNG: the magic bytes disagree.
        assertThatThrownBy(() -> service.upload(admin, EXE, "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRealImageDeclaredAsAnotherTypeIsRefused() {
        assertThatThrownBy(() -> service.upload(admin, JPEG, "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void emptyAndOversizedUploadsAreRefused() {
        assertThatThrownBy(() -> service.upload(admin, new byte[0], "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        byte[] big = Arrays.copyOf(PNG, DisplayPolicyAssetService.MAX_ASSET_BYTES + 1);
        assertThatThrownBy(() -> service.upload(admin, big, "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void reuploadingTheSameBytesIsIdempotent() {
        String sha = DisplayPolicyAssetService.sha256Hex(PNG);
        EndpointDisplayPolicyAsset stored = new EndpointDisplayPolicyAsset(TENANT, sha, "image/png", PNG, "x", NOW);
        when(assetRepository.findByTenantIdAndSha256(TENANT, sha)).thenReturn(Optional.of(stored));

        AdminDisplayPolicyAssetResponse res = service.upload(admin, PNG, "image/png");

        assertThat(res.created()).isFalse();
        assertThat(res.assetSha256()).isEqualTo(sha);
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void aConcurrentIdenticalUploadResolvesToTheStoredRow() {
        String sha = DisplayPolicyAssetService.sha256Hex(PNG);
        EndpointDisplayPolicyAsset winner = new EndpointDisplayPolicyAsset(TENANT, sha, "image/png", PNG, "x", NOW);
        when(assetRepository.findByTenantIdAndSha256(TENANT, sha))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(assetRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ux_edpa"));

        AdminDisplayPolicyAssetResponse res = service.upload(admin, PNG, "image/png");

        assertThat(res.created()).isFalse();
        assertThat(res.assetSha256()).isEqualTo(sha);
    }

    @Test
    void uploadIsUnavailableWhileTheSurfaceIsDisabled() {
        assertThatThrownBy(() -> service(false).upload(admin, PNG, "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ── agent download ───────────────────────────────────────────────────────

    private String storeAssetAndPolicy(DisplayPolicyOperation op, Instant clearedAt, String assetRefOverride) {
        String sha = DisplayPolicyAssetService.sha256Hex(PNG);
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getTenantId()).thenReturn(TENANT);
        when(deviceRepository.findById(DEVICE)).thenReturn(Optional.of(device));

        EndpointDisplayPolicy current = mock(EndpointDisplayPolicy.class);
        when(current.getOperation()).thenReturn(op);
        when(current.getClearedAt()).thenReturn(clearedAt);
        when(current.getWallpaperAssetSha256()).thenReturn(sha);
        when(current.getWallpaperAssetRef()).thenReturn(
                assetRefOverride != null ? assetRefOverride : "asset:sha256:" + sha);
        when(policyRepository.findByTenantIdAndDeviceId(TENANT, DEVICE)).thenReturn(Optional.of(current));

        when(assetRepository.findByTenantIdAndSha256(TENANT, sha)).thenReturn(Optional.of(
                new EndpointDisplayPolicyAsset(TENANT, sha, "image/png", PNG, "maker-admin", NOW)));
        return sha;
    }

    @Test
    void aDeviceGetsTheImageItsApprovedPolicyNames() {
        String sha = storeAssetAndPolicy(DisplayPolicyOperation.ENFORCE, null, null);

        EndpointDisplayPolicyAsset asset = service.loadForDevice(DEVICE.toString(), sha);

        assertThat(asset.getContent()).isEqualTo(PNG);
        assertThat(asset.getContentType()).isEqualTo("image/png");
    }

    @Test
    void aDeviceCannotFetchAnImageItsPolicyDoesNotName() {
        storeAssetAndPolicy(DisplayPolicyOperation.ENFORCE, null, null);
        String other = DisplayPolicyAssetService.sha256Hex("other".getBytes(StandardCharsets.UTF_8));

        assertNotFound(() -> service.loadForDevice(DEVICE.toString(), other));
    }

    @Test
    void aClearedPolicyNoLongerGrantsItsImage() {
        String sha = storeAssetAndPolicy(DisplayPolicyOperation.ENFORCE, NOW, null);
        assertNotFound(() -> service.loadForDevice(DEVICE.toString(), sha));
    }

    @Test
    void aClearOperationGrantsNothing() {
        String sha = storeAssetAndPolicy(DisplayPolicyOperation.CLEAR, null, null);
        assertNotFound(() -> service.loadForDevice(DEVICE.toString(), sha));
    }

    @Test
    void aPolicyThatPointsAtALocalPathDoesNotUnlockAStoredImage() {
        // Same hash, but the policy tells the agent to use a file already on disk:
        // it never asked for a download, so none is granted.
        String sha = storeAssetAndPolicy(DisplayPolicyOperation.ENFORCE, null, "C:\\Wallpapers\\corp.png");
        assertNotFound(() -> service.loadForDevice(DEVICE.toString(), sha));
    }

    @Test
    void malformedInputsAreIndistinguishableFromAbsentOnes() {
        storeAssetAndPolicy(DisplayPolicyOperation.ENFORCE, null, null);
        assertNotFound(() -> service.loadForDevice(DEVICE.toString(), "../../etc/passwd"));
        assertNotFound(() -> service.loadForDevice("not-a-uuid", "a".repeat(64)));
        assertNotFound(() -> service.loadForDevice(null, "a".repeat(64)));
        assertNotFound(() -> service.loadForDevice(UUID.randomUUID().toString(),
                DisplayPolicyAssetService.sha256Hex(PNG)));
    }

    // ── policy-time check ────────────────────────────────────────────────────

    @Test
    void anEnforceCannotNameAnImageThatWasNeverUploaded() {
        assertThatThrownBy(() -> service.requireManagedAsset(TENANT, "c".repeat(64), "image/png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void anEnforceMustDeclareTheStoredType() {
        String sha = DisplayPolicyAssetService.sha256Hex(PNG);
        when(assetRepository.findByTenantIdAndSha256(TENANT, sha)).thenReturn(Optional.of(
                new EndpointDisplayPolicyAsset(TENANT, sha, "image/png", PNG, "x", NOW)));

        assertThat(service.requireManagedAsset(TENANT, sha, "image/png").getSha256()).isEqualTo(sha);
        assertThatThrownBy(() -> service.requireManagedAsset(TENANT, sha, "image/jpeg"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void magicByteDetectionRecognisesOnlyTheThreeFormats() {
        assertThat(DisplayPolicyAssetService.detectContentType(PNG)).isEqualTo("image/png");
        assertThat(DisplayPolicyAssetService.detectContentType(JPEG)).isEqualTo("image/jpeg");
        assertThat(DisplayPolicyAssetService.detectContentType(BMP)).isEqualTo("image/bmp");
        assertThat(DisplayPolicyAssetService.detectContentType(EXE)).isNull();
        assertThat(DisplayPolicyAssetService.detectContentType(new byte[] {'B'})).isNull();
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}

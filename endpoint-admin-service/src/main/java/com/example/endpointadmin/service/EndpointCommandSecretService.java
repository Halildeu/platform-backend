package com.example.endpointadmin.service;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandSecret;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandSecretRepository;
import com.example.endpointadmin.security.DeviceSecretProtector;
import com.example.endpointadmin.security.ProtectedDeviceSecret;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnPrimaryEndpointPlane
public class EndpointCommandSecretService {

    private static final String LOCAL_PASSWORD_SECRET_NAME = "newPassword";
    private static final String TPM_ENROLLMENT_TOKEN_SECRET_NAME = "enrollmentToken";
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGIT = "23456789".toCharArray();
    private static final char[] SYMBOL = "!@#%+-_=.".toCharArray();
    private static final char[] ALL = (new String(UPPER) + new String(LOWER)
            + new String(DIGIT) + new String(SYMBOL)).toCharArray();

    private final EndpointCommandSecretRepository repository;
    private final DeviceSecretProtector secretProtector;
    private final EndpointAuditService auditService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public EndpointCommandSecretService(EndpointCommandSecretRepository repository,
                                        DeviceSecretProtector secretProtector,
                                        EndpointAuditService auditService,
                                        Clock clock) {
        this.repository = repository;
        this.secretProtector = secretProtector;
        this.auditService = auditService;
        this.clock = clock;
    }

    public String generateLocalPassword() {
        List<Character> chars = new ArrayList<>();
        chars.add(randomFrom(UPPER));
        chars.add(randomFrom(LOWER));
        chars.add(randomFrom(DIGIT));
        chars.add(randomFrom(SYMBOL));
        while (chars.size() < 20) {
            chars.add(randomFrom(ALL));
        }
        Collections.shuffle(chars, secureRandom);
        StringBuilder builder = new StringBuilder(chars.size());
        for (Character ch : chars) {
            builder.append(ch.charValue());
        }
        return builder.toString();
    }

    @Transactional
    public EndpointCommandSecret createLocalPasswordSecret(UUID tenantId,
                                                           EndpointDevice device,
                                                           EndpointCommand command,
                                                           String plainPassword,
                                                           Instant expiresAt,
                                                           String requestedBy,
                                                           String username,
                                                           String reason) {
        return createSecret(tenantId, device, command, plainPassword, expiresAt,
                requestedBy, LOCAL_PASSWORD_SECRET_NAME,
                Map.of("username", username, "reason", reason));
    }

    @Transactional
    public EndpointCommandSecret createTpmEnrollmentTokenSecret(
            UUID tenantId,
            EndpointDevice device,
            EndpointCommand command,
            String plainToken,
            Instant expiresAt,
            String requestedBy,
            UUID enrollmentId,
            String reason) {
        return createSecret(tenantId, device, command, plainToken, expiresAt,
                requestedBy, TPM_ENROLLMENT_TOKEN_SECRET_NAME,
                Map.of("enrollmentId", enrollmentId.toString(), "reason", reason));
    }

    private EndpointCommandSecret createSecret(UUID tenantId,
                                               EndpointDevice device,
                                               EndpointCommand command,
                                               String plainValue,
                                               Instant expiresAt,
                                               String requestedBy,
                                               String secretName,
                                               Map<String, Object> metadata) {
        ProtectedDeviceSecret protectedSecret = secretProtector.protect(plainValue);
        EndpointCommandSecret secret = new EndpointCommandSecret();
        secret.setTenantId(tenantId);
        secret.setCommand(command);
        secret.setSecretName(secretName);
        secret.setEncryptedSecret(protectedSecret.encryptedSecret());
        secret.setEncryptionKeyVersion(protectedSecret.encryptionKeyVersion());
        secret.setExpiresAt(expiresAt);
        EndpointCommandSecret saved = repository.saveAndFlush(secret);

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("commandType", command.getCommandType().name());
        auditMetadata.put("secretName", secretName);
        auditMetadata.put("secretId", saved.getId().toString());
        auditMetadata.put("expiresAt", expiresAt.toString());
        auditMetadata.putAll(metadata);
        auditService.record(
                tenantId,
                device,
                command,
                "ENDPOINT_COMMAND_SECRET_CREATED",
                "CREATE_COMMAND_SECRET",
                requestedBy,
                command.getIdempotencyKey(),
                auditMetadata,
                null,
                Map.of("secretState", "ACTIVE"));
        return saved;
    }

    public Map<String, Object> payloadForAgentClaim(EndpointCommand command,
                                                    Map<String, Object> storedPayload,
                                                    String claimId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (storedPayload != null) {
            payload.putAll(storedPayload);
        }
        if (!usesEncryptedCommandSecret(command.getCommandType())) {
            return payload;
        }

        Instant now = Instant.now(clock);
        EndpointCommandSecret secret = repository.findByCommandIdForUpdate(command.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE,
                        "Command secret is unavailable."));
        if (secret.getClearedAt() != null || secret.getEncryptedSecret() == null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Command secret has already been cleared.");
        }
        if (!secret.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Command secret has expired.");
        }
        if (secret.getDeliveredAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Command secret was already delivered.");
        }

        payload.put(secret.getSecretName(), secretProtector.reveal(secret.getEncryptedSecret()));
        secret.setDeliveredAt(now);
        repository.saveAndFlush(secret);
        auditService.record(
                command.getTenantId(),
                command.getDevice(),
                command,
                "ENDPOINT_COMMAND_SECRET_DELIVERED",
                "DELIVER_COMMAND_SECRET",
                command.getDevice() == null ? null : command.getDevice().getId().toString(),
                command.getIdempotencyKey(),
                Map.of("commandType", command.getCommandType().name(),
                        "secretName", secret.getSecretName(),
                        "secretId", secret.getId().toString(),
                        "claimId", claimId),
                null,
                Map.of("secretState", "DELIVERED"));
        return payload;
    }

    public void clearIfTerminal(EndpointCommand command) {
        if (!usesEncryptedCommandSecret(command.getCommandType())) {
            return;
        }
        repository.findByCommandIdForUpdate(command.getId()).ifPresent(secret -> {
            if (secret.getClearedAt() == null) {
                Instant now = Instant.now(clock);
                secret.setEncryptedSecret(null);
                secret.setClearedAt(now);
                repository.saveAndFlush(secret);
                auditService.record(
                        command.getTenantId(),
                        command.getDevice(),
                        command,
                        "ENDPOINT_COMMAND_SECRET_CLEARED",
                        "CLEAR_COMMAND_SECRET",
                        command.getDevice() == null ? null : command.getDevice().getId().toString(),
                        command.getIdempotencyKey(),
                        Map.of("commandType", command.getCommandType().name(),
                                "secretName", secret.getSecretName(),
                                "secretId", secret.getId().toString()),
                        Map.of("secretState", "DELIVERED"),
                        Map.of("secretState", "CLEARED"));
            }
        });
    }

    private boolean usesEncryptedCommandSecret(CommandType commandType) {
        return commandType == CommandType.CHANGE_LOCAL_PASSWORD
                || commandType == CommandType.RENEW_TPM_CERTIFICATE;
    }

    private Character randomFrom(char[] alphabet) {
        return alphabet[secureRandom.nextInt(alphabet.length)];
    }
}

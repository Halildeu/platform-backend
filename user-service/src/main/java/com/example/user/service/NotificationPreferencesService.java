package com.example.user.service;

import com.example.user.dto.v1.NotificationPreferenceUpdateDto;
import com.example.user.model.UserNotificationPreference;
import com.example.user.repository.UserNotificationPreferenceRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationPreferencesService {

    public static final List<String> DEFAULT_CHANNELS = List.of("email", "sms", "push", "in_app");
    private static final Set<String> ALLOWED_CHANNELS = Set.copyOf(DEFAULT_CHANNELS);
    private static final String NOTIFICATION_PREFERENCE_SYNC_CONFLICT_CODE = "USER_NOTIFICATION_PREFERENCE_SYNC_CONFLICT";
    private static final String STALE_EXPECTED_VERSION_REASON = "STALE_EXPECTED_VERSION";
    private static final Set<String> ALLOWED_FREQUENCIES = Set.of("immediate", "daily", "weekly");
    private static final String DEFAULT_FREQUENCY = "immediate";

    private final UserNotificationPreferenceRepository repository;
    private final UserAuditEventService userAuditEventService;

    public record NotificationPreferenceSyncResult(String status,
                                                   String channel,
                                                   boolean enabled,
                                                   String frequency,
                                                   Integer version,
                                                   String auditId,
                                                   String source,
                                                   String message,
                                                   String errorCode,
                                                   String conflictReason) {
        public boolean isConflict() {
            return "conflict".equalsIgnoreCase(status);
        }
    }

    public NotificationPreferencesService(UserNotificationPreferenceRepository repository,
                                          UserAuditEventService userAuditEventService) {
        this.repository = repository;
        this.userAuditEventService = userAuditEventService;
    }

    @Transactional
    public List<UserNotificationPreference> getOrInitForUser(Long userId) {
        Map<String, UserNotificationPreference> existing = new HashMap<>();
        for (UserNotificationPreference pref : repository.findByUserId(userId)) {
            if (pref.getChannel() != null) {
                existing.put(normalizeChannel(pref.getChannel()), pref);
            }
        }

        boolean created = false;
        for (String channel : DEFAULT_CHANNELS) {
            if (!existing.containsKey(channel)) {
                UserNotificationPreference pref = new UserNotificationPreference();
                pref.setUserId(userId);
                pref.setChannel(channel);
                pref.setEnabled(true);
                pref.setFrequency(DEFAULT_FREQUENCY);
                pref.setUpdatedAt(LocalDateTime.now());
                repository.save(pref);
                existing.put(channel, pref);
                created = true;
            }
        }

        List<UserNotificationPreference> result = existing.values().stream()
                .sorted(Comparator.comparing(UserNotificationPreference::getChannel))
                .toList();
        return created ? repository.findByUserId(userId).stream().sorted(Comparator.comparing(UserNotificationPreference::getChannel)).toList() : result;
    }

    @Transactional
    public List<UserNotificationPreference> updateForUser(Long userId, List<NotificationPreferenceUpdateDto> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_preferences");
        }

        for (NotificationPreferenceUpdateDto update : updates) {
            String channel = normalizeChannel(update == null ? null : update.getChannel());
            if (!StringUtils.hasText(channel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel");
            }
            if (!ALLOWED_CHANNELS.contains(channel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown_channel");
            }
            if (update.getEnabled() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_enabled");
            }
            String frequency = normalizeFrequency(update.getFrequency());
            if (frequency != null && !ALLOWED_FREQUENCIES.contains(frequency)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_frequency");
            }

            UserNotificationPreference pref = repository.findByUserIdAndChannel(userId, channel)
                    .orElseGet(() -> {
                        UserNotificationPreference fresh = new UserNotificationPreference();
                        fresh.setUserId(userId);
                        fresh.setChannel(channel);
                        return fresh;
                    });
            pref.setEnabled(update.getEnabled());
            pref.setFrequency(frequency);
            pref.setUpdatedAt(LocalDateTime.now());
            repository.save(pref);
        }

        return getOrInitForUser(userId);
    }

    @Transactional
    public UserNotificationPreference getOrInitChannelForUser(Long userId, String channel) {
        String normalizedChannel = requireChannel(channel);
        List<UserNotificationPreference> preferences = getOrInitForUser(userId);
        return preferences.stream()
                .filter(pref -> normalizedChannel.equals(normalizeChannel(pref.getChannel())))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification_preference_not_found"));
    }

    @Transactional
    public NotificationPreferenceSyncResult syncOwnPreference(Long userId,
                                                              String userEmail,
                                                              String channel,
                                                              Boolean requestedEnabled,
                                                              String requestedFrequency,
                                                              Integer expectedVersion,
                                                              String source,
                                                              Integer attemptCount,
                                                              String queueActionId) {
        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected_version_required");
        }
        if (requestedEnabled == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_enabled");
        }

        UserNotificationPreference preference = getOrInitChannelForUser(userId, channel);
        Integer currentVersion = safeVersion(preference);
        if (!currentVersion.equals(expectedVersion)) {
            return buildConflictResult(preference, userId, userEmail, source, attemptCount, queueActionId);
        }

        String normalizedFrequency = resolveRequestedFrequency(preference, requestedFrequency);
        boolean previousEnabled = preference.isEnabled();
        String previousFrequency = preference.getFrequency();

        preference.setEnabled(requestedEnabled);
        preference.setFrequency(normalizedFrequency);
        preference.setUpdatedAt(LocalDateTime.now());

        try {
            UserNotificationPreference saved = repository.saveAndFlush(preference);
            String auditId = null;
            if (previousEnabled != saved.isEnabled()
                    || !java.util.Objects.equals(previousFrequency, saved.getFrequency())) {
                var event = userAuditEventService.recordNotificationPreferenceSyncEvent(
                        userId,
                        userId,
                        userEmail,
                        saved.getChannel(),
                        previousEnabled,
                        previousFrequency,
                        saved.isEnabled(),
                        saved.getFrequency(),
                        expectedVersion,
                        safeVersion(saved),
                        source,
                        attemptCount,
                        queueActionId
                );
                if (event != null && event.getId() != null) {
                    auditId = event.getId().toString();
                }
            }

            return new NotificationPreferenceSyncResult(
                    "ok",
                    saved.getChannel(),
                    saved.isEnabled(),
                    saved.getFrequency(),
                    safeVersion(saved),
                    auditId,
                    normalizeSource(source),
                    "Notification preference synced.",
                    null,
                    null
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            UserNotificationPreference latest = getOrInitChannelForUser(userId, channel);
            return buildConflictResult(latest, userId, userEmail, source, attemptCount, queueActionId);
        }
    }

    private NotificationPreferenceSyncResult buildConflictResult(UserNotificationPreference preference,
                                                                 Long userId,
                                                                 String userEmail,
                                                                 String source,
                                                                 Integer attemptCount,
                                                                 String queueActionId) {
        String auditId = null;
        var event = userAuditEventService.recordNotificationPreferenceConflictEvent(
                userId,
                userId,
                userEmail,
                preference.getChannel(),
                preference.isEnabled(),
                preference.getFrequency(),
                safeVersion(preference),
                source,
                STALE_EXPECTED_VERSION_REASON,
                attemptCount,
                queueActionId
        );
        if (event != null && event.getId() != null) {
            auditId = event.getId().toString();
        }

        return new NotificationPreferenceSyncResult(
                "conflict",
                preference.getChannel(),
                preference.isEnabled(),
                preference.getFrequency(),
                safeVersion(preference),
                auditId,
                normalizeSource(source),
                "Notification preference version changed before replay.",
                NOTIFICATION_PREFERENCE_SYNC_CONFLICT_CODE,
                STALE_EXPECTED_VERSION_REASON
        );
    }

    private String requireChannel(String channel) {
        String normalized = normalizeChannel(channel);
        if (!StringUtils.hasText(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel");
        }
        if (!ALLOWED_CHANNELS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown_channel");
        }
        return normalized;
    }

    private String resolveRequestedFrequency(UserNotificationPreference preference, String requestedFrequency) {
        if (!StringUtils.hasText(requestedFrequency)) {
            return preference.getFrequency();
        }
        String normalizedFrequency = normalizeFrequency(requestedFrequency);
        if (normalizedFrequency != null && !ALLOWED_FREQUENCIES.contains(normalizedFrequency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_frequency");
        }
        return normalizedFrequency;
    }

    private int safeVersion(UserNotificationPreference preference) {
        if (preference == null || preference.getVersion() == null || preference.getVersion() < 0) {
            return 0;
        }
        return preference.getVersion();
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim() : "mobile-offline-queue";
    }

    private static String normalizeChannel(String channel) {
        if (channel == null) return null;
        String normalized = channel.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("in-app")) return "in_app";
        return normalized;
    }

    private static String normalizeFrequency(String frequency) {
        if (!StringUtils.hasText(frequency)) return null;
        return frequency.trim().toLowerCase(Locale.ROOT);
    }
}

package com.example.user.service;

import com.example.user.dto.v1.NotificationPreferenceUpdateDto;
import com.example.user.model.UserNotificationPreference;
import com.example.user.repository.UserNotificationPreferenceRepository;
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

    private static final Set<String> ALLOWED_FREQUENCIES = Set.of("immediate", "daily", "weekly");
    private static final String DEFAULT_FREQUENCY = "immediate";

    private final UserNotificationPreferenceRepository repository;

    public NotificationPreferencesService(UserNotificationPreferenceRepository repository) {
        this.repository = repository;
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


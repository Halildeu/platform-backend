package com.example.permission.config;

import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(20)
public class DefaultAdminRoleAssignmentInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminRoleAssignmentInitializer.class);
    private static final Pattern QUALIFIED_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final JdbcTemplate jdbcTemplate;
    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final boolean enabled;
    private final List<String> adminEmails;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final String userTable;

    public DefaultAdminRoleAssignmentInitializer(
            JdbcTemplate jdbcTemplate,
            RoleRepository roleRepository,
            UserRoleAssignmentRepository assignmentRepository,
            @Value("${permission.bootstrap.default-admin-assignments.enabled:false}") boolean enabled,
            @Value("${permission.bootstrap.default-admin-assignments.admin-emails:admin@example.com,admin1@example.com}") String adminEmails,
            @Value("${permission.bootstrap.default-admin-assignments.max-attempts:10}") int maxAttempts,
            @Value("${permission.bootstrap.default-admin-assignments.retry-delay-ms:2000}") long retryDelayMs,
            @Value("${permission.bootstrap.default-admin-assignments.user-table:users}") String userTable
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.enabled = enabled;
        this.adminEmails = parseEmails(adminEmails);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
        this.userTable = normalizeTableName(userTable);
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (adminEmails.isEmpty()) {
            log.info("Default admin role assignment bootstrap atlandi: email listesi bos.");
            return;
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<Role> adminRoleOpt = roleRepository.findByNameIgnoreCase("ADMIN");
            if (adminRoleOpt.isEmpty()) {
                log.info("ADMIN rolu henuz hazir degil; bootstrap tekrar denenecek. attempt={}/{}", attempt, maxAttempts);
                if (!sleepBeforeRetry(attempt)) {
                    return;
                }
                continue;
            }

            Role adminRole = adminRoleOpt.get();
            try {
                Map<String, Long> discoveredUsers = loadUserIdsByEmail(adminEmails);
                seedAssignments(adminRole, discoveredUsers);

                List<String> missingEmails = adminEmails.stream()
                        .filter(email -> !discoveredUsers.containsKey(email))
                        .toList();
                if (missingEmails.isEmpty()) {
                    log.info("Default admin role assignment bootstrap tamamlandi. emails={}", adminEmails);
                    return;
                }

                log.info("Default admin role assignment bootstrap bekliyor; kullanicilar henuz hazir degil. missing={} attempt={}/{}",
                        missingEmails, attempt, maxAttempts);
            } catch (DataAccessException ex) {
                log.info("Default admin role assignment bootstrap bekliyor; user lookup tablosu henuz hazir degil. table={} attempt={}/{} reason={}",
                        userTable, attempt, maxAttempts, ex.getClass().getSimpleName());
            }

            if (!sleepBeforeRetry(attempt)) {
                return;
            }
        }

        log.warn("Default admin role assignment bootstrap zaman asimina ugradi. emails={}", adminEmails);
    }

    private void seedAssignments(Role adminRole, Map<String, Long> discoveredUsers) {
        for (Map.Entry<String, Long> entry : discoveredUsers.entrySet()) {
            Long userId = entry.getValue();
            if (userId == null) {
                continue;
            }
            boolean exists = assignmentRepository.findActiveAssignment(userId, null, adminRole.getId(), null, null).isPresent();
            if (exists) {
                continue;
            }
            UserRoleAssignment assignment = new UserRoleAssignment();
            assignment.setUserId(userId);
            assignment.setRole(adminRole);
            assignment.setActive(true);
            assignment.setAssignedAt(Instant.now());
            assignment.setAssignedBy(null);
            assignmentRepository.save(assignment);
            log.info("Default ADMIN role assignment olusturuldu. email={} userId={}", entry.getKey(), userId);
        }
    }

    private Map<String, Long> loadUserIdsByEmail(List<String> emails) {
        String placeholders = emails.stream().map(email -> "?").collect(Collectors.joining(", "));
        String sql = "select id, email from " + userTable + " where lower(email) in (" + placeholders + ")";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, emails.toArray());
        Map<String, Long> userIdsByEmail = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object emailValue = row.get("email");
            Object idValue = row.get("id");
            if (!(emailValue instanceof String email) || idValue == null) {
                continue;
            }
            userIdsByEmail.put(email.toLowerCase(Locale.ROOT), ((Number) idValue).longValue());
        }
        return userIdsByEmail;
    }

    private boolean sleepBeforeRetry(int attempt) {
        if (attempt >= maxAttempts || retryDelayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Default admin role assignment bootstrap kesildi.");
            return false;
        }
    }

    private static List<String> parseEmails(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> emails = new ArrayList<>();
        for (String token : csv.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                emails.add(normalized);
            }
        }
        return List.copyOf(emails);
    }

    private static String normalizeTableName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!QUALIFIED_TABLE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid permission bootstrap user table reference: " + raw);
        }
        return value;
    }
}

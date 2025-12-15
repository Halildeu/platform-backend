package com.example.user.controller;

import com.example.user.dto.KeycloakUserProvisionRequest;
import com.example.user.dto.v1.PagedUserResponseDto;
import com.example.user.dto.v1.UserActivationRequestDto;
import com.example.user.dto.v1.UserDetailDto;
import com.example.user.dto.v1.UserDtoMapper;
import com.example.user.dto.v1.UserSummaryDto;
import com.example.user.dto.v1.UserMutationAckDto;
import com.example.commonauth.AuthorizationContext;
import com.example.user.authz.AuthorizationContextService;
import com.example.user.model.User;
import com.example.user.permission.PermissionActions;
import com.example.user.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.user.security.ServiceAuthenticationToken;

@RestController
@RequestMapping("/api/v1/users")
// STORY-0032: User REST/DTO v1 Migration
public class UserControllerV1 {

    private final UserService userService;
    private final AuthorizationContextService authorizationContextService;
    private final MeterRegistry meterRegistry;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(UserControllerV1.class);

    public UserControllerV1(UserService userService,
                            AuthorizationContextService authorizationContextService,
                            MeterRegistry meterRegistry) {
        this.userService = userService;
        this.authorizationContextService = authorizationContextService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping
    public ResponseEntity<PagedUserResponseDto> listUsers(@RequestHeader(value = "X-Company-Id", required = false) Long companyId,
                                                          @RequestParam(value = "search", required = false) String search,
                                                          @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
                                                          @RequestParam(value = "role", required = false, defaultValue = "ALL") String role,
                                                          @RequestParam(value = "sort", required = false) String sort,
                                                          @RequestParam(value = "advancedFilter", required = false) String advancedFilter,
                                                          @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                          @RequestParam(value = "pageSize", required = false, defaultValue = "25") int pageSize,
                                                          @RequestParam(value = "dataSource", required = false) String dataSource) {
        User currentUser = requireCurrentUser();
        Timer.Sample sample = Timer.start(meterRegistry);
        String resultTag = "success";
        boolean hasSearchParam = StringUtils.hasText(search);
        boolean hasAdvancedFilterParam = StringUtils.hasText(advancedFilter);
        boolean hasSortParam = StringUtils.hasText(sort);

        requirePermissionWithCompanyScope(PermissionActions.USER_READ, companyId);

        Sort parsedSort = parseSort(sort);
        org.springframework.data.jpa.domain.Specification<User> extraSpec = buildAdvancedFilterSpecSafe(advancedFilter);
        boolean clientMode = dataSource != null && dataSource.equalsIgnoreCase("client");
        boolean unpaged = clientMode || pageSize <= 0;
        String modeTag = unpaged ? "client" : "server";

        try {
            if (unpaged) {
                List<UserSummaryDto> items = userService.searchUsers(search, status, role, extraSpec, parsedSort).stream()
                        .map(UserDtoMapper::toSummary)
                        .collect(Collectors.toList());
                PagedUserResponseDto response = new PagedUserResponseDto(items, items.size(), 1, items.size());
                return ResponseEntity.ok(response);
            }

            int safePage = Math.max(page, 1) - 1;
            int safePageSize = Math.max(pageSize, 1);
            Pageable pageable = PageRequest.of(safePage, safePageSize, parsedSort);

            Page<User> result = userService.searchUsers(search, status, role, extraSpec, pageable);

            List<UserSummaryDto> items = result.getContent().stream()
                    .map(UserDtoMapper::toSummary)
                    .collect(Collectors.toList());

            PagedUserResponseDto response = new PagedUserResponseDto(
                    items,
                    result.getTotalElements(),
                    result.getNumber() + 1,
                    result.getSize());

            return ResponseEntity.ok(response);
        } catch (ResponseStatusException ex) {
            resultTag = "error";
            throw ex;
        } finally {
            recordSearchMetrics(sample, modeTag, hasSearchParam, hasAdvancedFilterParam, hasSortParam, resultTag);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDetailDto> getUser(@PathVariable Long id) {
        User user = userService.findRequiredById(id);
        return ResponseEntity.ok(UserDtoMapper.toDetail(user));
    }

    @GetMapping("/by-email")
    public ResponseEntity<UserDetailDto> getUserByEmail(@RequestParam("email") String email) {
        return userService.findByEmail(email)
                .map(UserDtoMapper::toDetail)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/activation")
    public ResponseEntity<UserMutationAckDto> updateActivation(@PathVariable Long id,
                                                               @Valid @RequestBody UserActivationRequestDto request) {
        User currentUser = requireCurrentUser();
        String auditId = userService.updateActivation(id, Boolean.TRUE.equals(request.getActive()), currentUser.getId());
        return ResponseEntity.ok(UserMutationAckDto.ok(auditId));
    }

    @PostMapping("/internal/provision")
    public ResponseEntity<UserDetailDto> provisionFromKeycloak(@Valid @RequestBody KeycloakUserProvisionRequest request) {
        requireServiceAuthority("PERM_users:internal");
        User user = userService.provisionFromKeycloak(request);
        return ResponseEntity.ok(UserDtoMapper.toDetail(user));
    }

    private User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User u) {
            return u;
        }

        String username;
        if (principal instanceof Jwt jwt) {
            Object rawUserId = jwt.getClaim("userId");
            Long userId = null;
            if (rawUserId instanceof Number num) {
                userId = num.longValue();
            } else if (rawUserId instanceof String str) {
                try {
                    userId = Long.parseLong(str);
                } catch (NumberFormatException ignored) {
                    userId = null;
                }
            }
            if (userId != null) {
                try {
                    return userService.findRequiredById(userId);
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                        LOGGER.warn("JWT userId mevcut ancak yerel profil yok: {}", userId);
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                    }
                    throw ex;
                }
            }

            username = firstNonBlank(
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"),
                    authentication.getName());
        } else {
            username = authentication.getName();
        }

        if (!StringUtils.hasText(username)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        return userService.findByEmail(username)
                .orElseThrow(() -> {
                    LOGGER.warn("Keycloak kullanıcısı bulundu ancak yerel profil yok: {}", username);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    private org.springframework.data.jpa.domain.Specification<User> buildAdvancedFilterSpecSafe(String advancedFilter) {
        if (advancedFilter == null || advancedFilter.isBlank()) return null;
        try {
            String decoded = java.net.URLDecoder.decode(advancedFilter, java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded);
            String logic = root.has("logic") ? root.get("logic").asText("and").toLowerCase() : "and";
            com.fasterxml.jackson.databind.JsonNode conds = root.get("conditions");
            if (conds == null || !conds.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
            }
            if (conds.isEmpty()) return null;

            java.util.List<org.springframework.data.jpa.domain.Specification<User>> specs = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode c : conds) {
                String field = c.has("field") ? c.get("field").asText() : null;
                String op = c.has("op") ? c.get("op").asText() : null;
                com.fasterxml.jackson.databind.JsonNode val = c.get("value");
                if (field == null || op == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_condition");
                }
                FieldType type = ALLOWED_FILTER_FIELDS.get(field);
                if (type == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_field");
                }
                org.springframework.data.jpa.domain.Specification<User> s;
                switch (op) {
                    case "equals":
                        s = (rootX, query, cb) -> cb.equal(rootX.get(field), parseValue(val, type));
                        break;
                    case "notEqual":
                        s = (rootX, query, cb) -> cb.notEqual(rootX.get(field), parseValue(val, type));
                        break;
                    case "contains":
                        if (type != FieldType.STRING)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.like(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "notContains":
                        if (type != FieldType.STRING)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> cb.notLike(cb.lower(rootX.get(field)), "%" + (val != null ? val.asText("").toLowerCase() : "") + "%");
                        break;
                    case "lessThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.lessThan(rootX.get(field), v);
                        };
                        break;
                    case "greaterThan":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        s = (rootX, query, cb) -> {
                            Comparable v = (Comparable) parseValue(val, type);
                            return cb.greaterThan(rootX.get(field), v);
                        };
                        break;
                    case "inRange":
                        if (type != FieldType.NUMBER && type != FieldType.DATE_TIME)
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                        com.fasterxml.jackson.databind.JsonNode v2 = c.get("value2");
                        if (val == null || v2 == null) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_inrange");
                        }
                        s = (rootX, query, cb) -> {
                            Comparable v1c = (Comparable) parseValue(val, type);
                            Comparable v2c = (Comparable) parseValue(v2, type);
                            return cb.between(rootX.get(field), v1c, v2c);
                        };
                        break;
                    default:
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_operator");
                }
                specs.add(s);
            }
            if (specs.isEmpty()) return null;
            org.springframework.data.jpa.domain.Specification<User> combined = specs.get(0);
            for (int i = 1; i < specs.size(); i++) {
                combined = "or".equals(logic) ? combined.or(specs.get(i)) : combined.and(specs.get(i));
            }
            return combined;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(UserControllerV1.class).warn("advancedFilter parse hatası", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter");
        }
    }

    private enum FieldType {STRING, DATE_TIME, NUMBER, BOOLEAN}

    private static final java.util.Map<String, FieldType> ALLOWED_FILTER_FIELDS = java.util.Map.of(
            "name", FieldType.STRING,
            "email", FieldType.STRING,
            "role", FieldType.STRING,
            "enabled", FieldType.BOOLEAN,
            "createDate", FieldType.DATE_TIME,
            "lastLogin", FieldType.DATE_TIME,
            "sessionTimeoutMinutes", FieldType.NUMBER
    );

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createDate");
        }
        String[] parts = sort.split(";");
        List<Sort.Order> orders = new ArrayList<>();
        for (String p : parts) {
            String[] fd = p.split(",");
            if (fd.length != 2) continue;
            String field = fd[0].trim();
            String dir = fd[1].trim().toLowerCase();
            if (!ALLOWED_SORT_FIELDS.contains(field)) continue;
            Sort.Direction direction = "desc".equals(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, field));
        }
        if (orders.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createDate");
        }
        return Sort.by(orders);
    }

    private static final java.util.Set<String> ALLOWED_SORT_FIELDS = java.util.Set.of(
            "createDate", "lastLogin", "email", "name", "role", "enabled"
    );

    private Object parseValue(com.fasterxml.jackson.databind.JsonNode val, FieldType type) {
        if (val == null || val.isNull()) return null;
        try {
            return switch (type) {
                case STRING -> val.asText();
                case BOOLEAN -> {
                    String t = val.asText().trim().toLowerCase();
                    if (!t.equals("true") && !t.equals("false")) throw new IllegalArgumentException("boolean");
                    yield Boolean.parseBoolean(t);
                }
                case NUMBER -> {
                    if (val.isNumber()) yield val.intValue();
                    yield Integer.parseInt(val.asText());
                }
                case DATE_TIME -> java.time.LocalDateTime.parse(val.asText());
            };
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_advanced_filter_value");
        }
    }

    private void recordSearchMetrics(Timer.Sample sample,
                                     String modeTag,
                                     boolean hasSearchParam,
                                     boolean hasAdvancedFilterParam,
                                     boolean hasSortParam,
                                     String resultTag) {
        try {
            sample.stop(meterRegistry.timer("users.search", "mode", modeTag,
                    "search", String.valueOf(hasSearchParam),
                    "advancedFilter", String.valueOf(hasAdvancedFilterParam),
                    "sort", String.valueOf(hasSortParam),
                    "result", resultTag));
        } catch (Exception ignored) {
            // metrics optional
        }
    }

    private void requireServiceAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ServiceAuthenticationToken serviceAuth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service token gerekli");
        }
        boolean hasAuthority = serviceAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!hasAuthority) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Yetersiz servis yetkisi");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void requirePermissionWithCompanyScope(String permission, Long companyId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        Jwt jwt = authentication.getPrincipal() instanceof Jwt j ? j : null;
        AuthorizationContext ctx = authorizationContextService.buildContext(jwt, new java.util.ArrayList<>(authentication.getAuthorities()));
        if (!ctx.hasPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu işlemi gerçekleştirmek için " + permission + " yetkisine sahip olmalısınız");
        }
        if (companyId != null && !ctx.canAccessCompany(companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu şirket için yetkiniz yok");
        }
    }
}

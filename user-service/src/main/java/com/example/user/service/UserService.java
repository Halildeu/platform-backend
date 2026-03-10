package com.example.user.service;

import com.example.user.dto.KeycloakUserProvisionRequest;
import com.example.user.dto.RegisterRequest;
import com.example.user.dto.UpdateUserRequest;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.authz.AuthorizationContextService;
import com.example.commonauth.AuthorizationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService implements UserDetailsService { // UserDetailsService arayüzünü uygular

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserAuditEventService userAuditEventService;
    private final AuthorizationContextService authorizationContextService;
    private final int maxSessionTimeoutMinutes;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       UserAuditEventService userAuditEventService,
                       AuthorizationContextService authorizationContextService,
                       @Value("${user.session-timeout.max-minutes:1440}") int configuredMaxSessionTimeoutMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userAuditEventService = userAuditEventService;
        this.authorizationContextService = authorizationContextService;
        this.maxSessionTimeoutMinutes = Math.max(User.DEFAULT_SESSION_TIMEOUT_MINUTES, configuredMaxSessionTimeoutMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void normalizeExistingSessionTimeouts() {
        int updated = userRepository.normalizeSessionTimeouts(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
        if (updated > 0) {
            log.info("Oturum süreleri varsayılan değere güncellendi. Etkilenen kayıt sayısı={}", updated);
        }
        int rolesUpdated = normalizeExistingRoles();
        if (rolesUpdated > 0) {
            log.info("Rol değerleri normalize edildi. Etkilenen kayıt sayısı={}", rolesUpdated);
        }
    }

    public User updateUser(Long userId, UpdateUserRequest updateRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        if (updateRequest.getName() != null && !updateRequest.getName().isBlank()) {
            user.setName(updateRequest.getName());
        }

        if (updateRequest.getRole() != null && !updateRequest.getRole().isBlank()) {
            user.setRole(updateRequest.getRole());
        }

        if (updateRequest.getEnabled() != null) {
            user.setEnabled(updateRequest.getEnabled());
        }

        if (updateRequest.getSessionTimeoutMinutes() != null) {
            int timeout = Math.max(1, Math.min(updateRequest.getSessionTimeoutMinutes(), maxSessionTimeoutMinutes));
            user.setSessionTimeoutMinutes(timeout);
        }

        return userRepository.save(user);
    }

    /**
     * Yeni bir kullanıcıyı kaydeder.
     * @param registerRequest Kayıt için gerekli bilgileri içeren DTO.
     * @return Kaydedilen User nesnesi.
     */
    public User registerUser(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalStateException("Bu email adresi zaten kullanılıyor.");
        }

        User newUser = new User();
        newUser.setName(registerRequest.getName());
        newUser.setEmail(registerRequest.getEmail());
        // Parolayı şifreleyerek kaydediyoruz.
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        newUser.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
        // Varsayılan olarak rolü "USER" ve hesabı "enabled" (aktif) olacak.

        return userRepository.save(newUser);
    }

    /**
     * Herkese açık kayıt uç noktası için kullanıcı oluşturur.
     * Hesap başlangıçta pasif (enabled=false) bırakılır, doğrulama sonrası admin tarafından aktifleştirilir.
     */
    public User registerUserPublic(RegisterRequest registerRequest) {
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new IllegalStateException("Bu email adresi zaten kullanılıyor.");
        }

        User newUser = new User();
        newUser.setName(registerRequest.getName());
        newUser.setEmail(registerRequest.getEmail());
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        newUser.setEnabled(false);
        newUser.setRole("USER");
        newUser.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);

        return userRepository.save(newUser);
    }

    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        if (!user.isEnabled()) {
            user.setEnabled(true);
            userRepository.save(user);
        }
    }

    public void updatePasswordInternal(Long userId, String rawPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    @Transactional
    public void updateLastLogin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı: " + userId));

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        log.info("Son giriş tarihi güncellendi userId={}", userId);
    }

    @Transactional
    public User provisionFromKeycloak(KeycloakUserProvisionRequest request) {
        String email = request.getEmail().trim();
        Optional<User> existing = userRepository.findByEmail(email);
        User target = existing.orElseGet(() -> {
            User fresh = new User();
            fresh.setEmail(email);
            fresh.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            fresh.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);
            fresh.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
            return fresh;
        });

        target.setName(request.getName());

        if (StringUtils.hasText(request.getRole())) {
            target.setRole(request.getRole());
        }

        if (request.getEnabled() != null) {
            target.setEnabled(request.getEnabled());
        }

        Integer requestedTimeout = request.getSessionTimeoutMinutes();
        if (requestedTimeout != null) {
            int safeTimeout = Math.max(1, Math.min(requestedTimeout, maxSessionTimeoutMinutes));
            target.setSessionTimeoutMinutes(safeTimeout);
        }

        return userRepository.save(target);
    }

    /**
     * Email adresine göre kullanıcıyı bulur.
     * @param email Aranacak email adresi.
     * @return Opsiyonel olarak User nesnesi.
     */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Transactional
    public User findRequiredByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Keycloak kullanıcısı bulundu ancak yerel profil yok: {}", email);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    public User findRequiredById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + id));
    }

    public String updateActivation(Long userId, boolean active, Long performedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı: " + userId));
        if (user.isEnabled() != active) {
            user.setEnabled(active);
            userRepository.save(user);
        }
        if (performedBy != null) {
            var event = userAuditEventService.recordActivationEvent(performedBy, userId, active);
            if (event != null && event.getId() != null) {
                return event.getId().toString();
            }
        }
        return null;
    }

    private int normalizeExistingRoles() {
        List<User> users = userRepository.findAll();
        List<User> toUpdate = new ArrayList<>();
        for (User user : users) {
            String normalized = User.normalizeRole(user.getRole());
            if (!normalized.equals(user.getRole())) {
                user.setRole(normalized);
                toUpdate.add(user);
            }
        }
        if (toUpdate.isEmpty()) {
            return 0;
        }
        userRepository.saveAll(toUpdate);
        return toUpdate.size();
    }

    public Page<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Pageable pageable) {
        return searchUsers(search, status, role, null, pageable);
    }

    public Page<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Specification<User> extraSpec,
                                  Pageable pageable) {
        Specification<User> spec = buildSpecification(search, status, role, extraSpec);

        AuthorizationContext authz = resolveCurrentAuthz();
        if (!authz.isAdmin()) {
            Set<Long> allowedCompanies = authz.getAllowedCompanyIds();
            Specification<User> companySpec = (root, query, cb) -> {
                if (allowedCompanies.isEmpty()) {
                    // company scope yoksa, yalnızca company_id IS NULL olan global kullanıcılar görünsün
                    return cb.isNull(root.get("companyId"));
                }
                // company scope varsa: allowedCompanyIds OR company_id IS NULL
                return cb.or(root.get("companyId").in(allowedCompanies), cb.isNull(root.get("companyId")));
            };
            spec = spec == null ? companySpec : spec.and(companySpec);
        }

        if (spec == null) {
            return userRepository.findAll(pageable);
        }

        return userRepository.findAll(spec, pageable);
    }

    public List<User> searchUsers(String search,
                                  String status,
                                  String role,
                                  Specification<User> extraSpec,
                                  Sort sort) {
        Specification<User> spec = buildSpecification(search, status, role, extraSpec);
        Sort safeSort = (sort == null || sort.isUnsorted())
                ? Sort.by(Sort.Direction.ASC, "id")
                : sort;

        AuthorizationContext authz = resolveCurrentAuthz();
        if (!authz.isAdmin()) {
            Set<Long> allowedCompanies = authz.getAllowedCompanyIds();
            Specification<User> companySpec = (root, query, cb) -> {
                if (allowedCompanies.isEmpty()) {
                    return cb.isNull(root.get("companyId"));
                }
                return cb.or(root.get("companyId").in(allowedCompanies), cb.isNull(root.get("companyId")));
            };
            spec = spec == null ? companySpec : spec.and(companySpec);
        }

        if (spec == null) {
            return userRepository.findAll(safeSort);
        }

        return userRepository.findAll(spec, safeSort);
    }

    private Specification<User> buildSpecification(String search,
                                                   String status,
                                                   String role,
                                                   Specification<User> extraSpec) {
        Specification<User> spec = null;

        if (StringUtils.hasText(search)) {
            String like = "%" + search.trim().toLowerCase() + "%";
            Specification<User> searchSpec = (root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("name")), like)
            );
            spec = spec == null ? searchSpec : spec.and(searchSpec);
        }

        if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status)) {
            if ("ACTIVE".equalsIgnoreCase(status)) {
                Specification<User> activeSpec = (root, query, cb) -> cb.isTrue(root.get("enabled"));
                spec = spec == null ? activeSpec : spec.and(activeSpec);
            } else if ("INACTIVE".equalsIgnoreCase(status)) {
                Specification<User> inactiveSpec = (root, query, cb) -> cb.isFalse(root.get("enabled"));
                spec = spec == null ? inactiveSpec : spec.and(inactiveSpec);
            }
        }

        if (StringUtils.hasText(role) && !"ALL".equalsIgnoreCase(role)) {
            String normalizedRole = role.trim().toUpperCase();
            Specification<User> roleSpec = (root, query, cb) -> cb.equal(cb.upper(root.get("role")), normalizedRole);
            spec = spec == null ? roleSpec : spec.and(roleSpec);
        }

        if (extraSpec != null) {
            spec = (spec == null) ? extraSpec : spec.and(extraSpec);
        }
        return spec;
    }

    private AuthorizationContext resolveCurrentAuthz() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return AuthorizationContext.of(null, null, Set.of(), Set.of());
        }
        List<GrantedAuthority> authorities = authentication.getAuthorities() == null
                ? List.of()
                : new ArrayList<>(authentication.getAuthorities());
        return authorizationContextService.buildContext(
                authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt ? jwt : null,
                authorities
        );
    }

    /**
     * UserDetailsService arayüzünden gelen ve Spring Security tarafından kullanılan metot.
     * Email (username) ile kullanıcıyı veritabanından yükler.
     * @param email Kullanıcı adı olarak kullanılan email.
     * @return UserDetails arayüzünü uygulayan bir User nesnesi.
     * @throws UsernameNotFoundException Kullanıcı bulunamazsa fırlatılır.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Kullanıcıyı kendi veritabanından bul ve döndür.
        // User modelimiz zaten UserDetails'i uyguladığı için doğrudan döndürebiliriz.
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Kullanıcı bulunamadı: " + email));
    }
}

package com.example.user.service;

import com.example.user.dto.RegisterRequest;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.repository.UserAuditEventRepository;
import com.example.user.authz.AuthorizationContextService;
import com.example.commonauth.AuthorizationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Mockito'yu JUnit 5 ile kullanmak için bu anotasyon gereklidir.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    // @Mock: Bu bağımlılıkların sahte (mock) versiyonları oluşturulacak.
    // Gerçek veritabanına veya parola şifreleyiciye gitmeyeceğiz.
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserAuditEventRepository userAuditEventRepository;

    private FakeAuthzService authorizationContextService;

    private UserAuditEventService userAuditEventService;

    // @InjectMocks: Test edeceğimiz asıl sınıf budur.
    // Mockito, yukarıda oluşturulan sahte (@Mock) nesneleri bu sınıfa enjekte eder.
    private UserService userService;

    private RegisterRequest registerRequest;
    private User user;
    private User userWithCompany;

    // Her testten önce bu metot çalışır ve test verilerini hazırlar.
    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setName("Test User");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPassword("password123");

        user = new User();
        user.setId(1L);
        user.setName("Test User");
        user.setEmail("test@example.com");
        user.setCompanyId(null); // global

        userWithCompany = new User();
        userWithCompany.setId(2L);
        userWithCompany.setName("Company User");
        userWithCompany.setEmail("c1@example.com");
        userWithCompany.setCompanyId(10L);

        userAuditEventService = new UserAuditEventService(userAuditEventRepository);
        authorizationContextService = new FakeAuthzService();
        userService = new UserService(userRepository, passwordEncoder, userAuditEventService, authorizationContextService, 1440);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerUser_WhenEmailDoesNotExist_ShouldRegisterSuccessfully() {
        // --- ARRANGE (Hazırlık) ---
        // Mockito'ya, userRepository.existsByEmail metodu çağrıldığında
        // ne olursa olsun 'false' döndürmesini söylüyoruz.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

        // Parola şifreleme metodunun sahte bir şifrelenmiş değer döndürmesini sağlıyoruz.
        when(passwordEncoder.encode("password123")).thenReturn("hashed_password");

        // userRepository.save metodu herhangi bir User nesnesiyle çağrıldığında,
        // hazırladığımız 'user' nesnesini döndürmesini söylüyoruz.
        when(userRepository.save(any(User.class))).thenReturn(user);

        // --- ACT (Eylem) ---
        // Test edeceğimiz asıl metodu çağırıyoruz.
        User savedUser = userService.registerUser(registerRequest);

        // --- ASSERT (Doğrulama) ---
        // Sonuçların beklediğimiz gibi olup olmadığını kontrol ediyoruz.
        assertNotNull(savedUser); // Kaydedilen kullanıcı null olmamalı.
        assertEquals("test@example.com", savedUser.getEmail()); // Email'ler eşleşmeli.
        System.out.println(">>> Başarılı kullanıcı kaydı testi geçti!");
    }

    @Test
    void registerUser_WhenEmailExists_ShouldThrowException() {
        // --- ARRANGE (Hazırlık) ---
        // Bu senaryoda, email'in zaten var olduğunu varsayıyoruz.
        // Mockito'ya, existsByEmail çağrıldığında 'true' döndürmesini söylüyoruz.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        // --- ACT & ASSERT (Eylem ve Doğrulama) ---
        // registerUser metodunun bir IllegalStateException fırlatıp fırlatmadığını test ediyoruz.
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            userService.registerUser(registerRequest);
        });

        // Fırlatılan hatanın mesajının doğru olup olmadığını kontrol ediyoruz.
        assertEquals("Bu email adresi zaten kullanılıyor.", exception.getMessage());
        System.out.println(">>> Mevcut email hatası testi geçti!");
    }

    @Test
    void searchUsers_superAdminSeesAll() {
        AuthorizationContext adminCtx = AuthorizationContext.of(1L, "admin@example.com", java.util.Set.of("ADMIN"), java.util.Set.of("ADMIN"));
        authorizationContextService.setCtx(adminCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> users = java.util.List.of(user, user);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(users, pageable, users.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchUsers_scopedUserGetsOnlyOwnCompanies() {
        AuthorizationContext scopedCtx = AuthorizationContext.of(2L, "user@example.com", java.util.Set.of(), java.util.Set.of("USER_READ"), java.util.Set.of(10L), java.util.Set.of(), java.util.Set.of());
        authorizationContextService.setCtx(scopedCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> companyUsers = java.util.List.of(userWithCompany, user); // company + global (null)
        when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(companyUsers, pageable, companyUsers.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void searchUsers_scopedUserWithoutCompaniesGetsEmpty() {
        AuthorizationContext scopedCtx = AuthorizationContext.of(2L, "user@example.com", java.util.Set.of(), java.util.Set.of("USER_READ"), java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
        authorizationContextService.setCtx(scopedCtx);
        Authentication authentication = new StubAuthentication(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        Pageable pageable = PageRequest.of(0, 10);
        java.util.List<User> globalOnly = java.util.List.of(user);
        when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(globalOnly, pageable, globalOnly.size()));

        Page<User> result = userService.searchUsers(null, null, null, null, pageable);

        assertEquals(1, result.getTotalElements()); // sadece company_id NULL kayıtlar görünür
        verify(userRepository, never()).findAll(any(Pageable.class));
        verify(userRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
    }
}

/**
 * Basit bir sahte AuthorizationContextService; gerçek HTTP çağrısı yapmaz.
 */
class FakeAuthzService extends AuthorizationContextService {

    private AuthorizationContext ctx = AuthorizationContext.of(null, null, java.util.Set.of(), java.util.Set.of());

    FakeAuthzService() {
        super(null, null, "");
    }

    void setCtx(AuthorizationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public AuthorizationContext buildContext(org.springframework.security.oauth2.jwt.Jwt jwt, java.util.List<org.springframework.security.core.GrantedAuthority> authorities) {
        return ctx;
    }

    @Override
    public AuthorizationContext getCurrentUserContext() {
        return ctx;
    }
}

/**
 * Basit Authentication stub'u; Mockito inline mock gerektirmeden SecurityContext'e konur.
 */
class StubAuthentication implements Authentication {
    private final Object principal;
    private boolean authenticated = true;

    StubAuthentication(Object principal) {
        this.principal = principal;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    @Override
    public String getName() {
        return principal != null ? principal.toString() : "";
    }
}

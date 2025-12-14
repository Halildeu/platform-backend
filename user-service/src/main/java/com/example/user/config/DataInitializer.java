package com.example.user.config; // Paket adını kendi yapınıza göre düzenleyin

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail("admin@example.com")) {
            return;
        }
        User adminUser = new User();
        adminUser.setName("Admin User");
        adminUser.setEmail("admin@example.com");
        adminUser.setPassword(passwordEncoder.encode("admin1234"));
        adminUser.setRole("ADMIN");
        adminUser.setEnabled(true);
        adminUser.setSessionTimeoutMinutes(User.DEFAULT_SESSION_TIMEOUT_MINUTES);

        userRepository.save(adminUser);
        log.info("Varsayılan ADMIN kullanıcısı oluşturuldu: admin@example.com");
    }
}

package com.example.auth.security;

import com.example.auth.user.InternalUserResponse;
import com.example.auth.user.UserServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);
    private final UserServiceClient userServiceClient;

    public CustomUserDetailsService(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        try {
            log.debug("Kullanıcı bilgisi user-service üzerinden çözümleniyor: email={}", email);
            InternalUserResponse userDto = userServiceClient.findInternalUserByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Bu email ile kullanıcı bulunamadı: " + email));

            if (userDto.getId() == null) {
                throw new UsernameNotFoundException("Kullanıcı ID bilgisi eksik: " + email);
            }

            Integer sessionTimeout = userDto.getSessionTimeoutMinutes();
            if (sessionTimeout == null || sessionTimeout < 1) {
                sessionTimeout = 15;
            }

            return new AuthenticatedUserDetails(
                    userDto.getId(),
                    userDto.getEmail(),
                    userDto.getPassword(),
                    userDto.isEnabled(),
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + userDto.getRole())),
                    sessionTimeout
            );
        } catch (Exception e) {
            throw new UsernameNotFoundException("Kullanıcı doğrulaması sırasında bir sorun oluştu: " + email, e);
        }
    }
}

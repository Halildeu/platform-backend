package com.example.auth.security;

import com.example.auth.dto.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);
    private static final String USER_SERVICE_AUDIENCE = "user-service";
    private static final String REQUIRED_PERMISSION = "users:internal";

    private final RestTemplate restTemplate;

    public CustomUserDetailsService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // "lb" load balancer anlamına gelir ve Eureka'dan "user-service"in adresini sorgular.
        String url = "http://user-service:8089/api/users/internal/by-email/" + email;
        log.debug("Kullanıcı bilgisi istek URL: {}", url);

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            ResponseEntity<UserDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, requestEntity, UserDto.class);

            // user-service'e API isteği at ve cevabı UserDto'ya çevir.
            UserDto userDto = response.getBody();

            if (userDto == null) {
                throw new UsernameNotFoundException("Kullanıcı bilgileri alınamadı: " + email);
            }

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

        } catch (HttpClientErrorException.NotFound ex) {
            // user-service 404 Not Found dönerse, bu kullanıcı yok demektir.
            throw new UsernameNotFoundException("Bu email ile kullanıcı bulunamadı: " + email, ex);
        } catch (Exception e) {
            // GERÇEK HATAYI KONSOLA YAZDIR
            e.printStackTrace();
            // RestTemplate ile ilgili başka bir sorun olursa (örn: user-service çalışmıyorsa)
            throw new UsernameNotFoundException("Kullanıcı doğrulaması sırasında bir sorun oluştu: " + email, e);
        }
    }

}

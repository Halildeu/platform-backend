package com.example.auth.user;

import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.example.auth.dto.RegisterRequest;
import com.example.auth.serviceauth.ServiceTokenProvider;

import java.util.List;

@Component
@Profile({"local", "dev"})
public class UserServiceClient {

    private static final String USER_SERVICE_AUDIENCE = "user-service";
    private static final String REQUIRED_PERMISSION = "users:internal";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ServiceTokenProvider serviceTokenProvider;

    public UserServiceClient(RestTemplate restTemplate,
                             @org.springframework.beans.factory.annotation.Value("${user.service.base-url:http://user-service:8089}") String baseUrl,
                             ServiceTokenProvider serviceTokenProvider) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public RemoteUserResponse registerPublicUser(RegisterRequest request) {
        String url = baseUrl + "/api/users/public/register";
        try {
            ResponseEntity<RemoteUserResponse> response = restTemplate.postForEntity(url, request, RemoteUserResponse.class);
            return response.getBody();
        } catch (HttpClientErrorException.Conflict ex) {
            throw new UserAlreadyExistsException("Bu email adresi zaten kayıtlı.", ex);
        }
    }

    public Optional<RemoteUserResponse> findUserByEmail(String email) {
        String url = baseUrl + "/api/users/by-email/" + email;
        try {
            ResponseEntity<RemoteUserResponse> response = restTemplate.getForEntity(url, RemoteUserResponse.class);
            return Optional.ofNullable(response.getBody());
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }

    public Optional<InternalUserResponse> findInternalUserByEmail(String email) {
        String url = baseUrl + "/api/users/internal/by-email/" + email;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION)));
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
            ResponseEntity<InternalUserResponse> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, InternalUserResponse.class);
            return Optional.ofNullable(response.getBody());
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }

    public void activateUser(Long userId) {
        String url = baseUrl + "/api/users/internal/" + userId + "/activate";
        HttpHeaders headers = internalHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);
        restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
    }

    public void updatePassword(Long userId, String newPassword) {
        String url = baseUrl + "/api/users/internal/" + userId + "/password";
        HttpHeaders headers = internalHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        PasswordUpdateRequest body = new PasswordUpdateRequest(newPassword);
        HttpEntity<PasswordUpdateRequest> request = new HttpEntity<>(body, headers);

        restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
    }

    public void updateLastLogin(Long userId) {
        String url = baseUrl + "/api/users/internal/" + userId + "/last-login";
        HttpHeaders headers = internalHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);

        restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION)));
        return headers;
    }

    private record PasswordUpdateRequest(String newPassword) {
    }
}

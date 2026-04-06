package com.example.auth.user;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.auth.dto.RegisterRequest;
import com.example.auth.serviceauth.ServiceTokenProvider;

import java.util.List;

@Component
public class UserServiceClient {

    private static final String USER_SERVICE_AUDIENCE = "user-service";
    private static final String REQUIRED_PERMISSION = "users:internal";

    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public UserServiceClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                             @org.springframework.beans.factory.annotation.Value("${user.service.base-url:http://user-service}") String baseUrl,
                             ServiceTokenProvider serviceTokenProvider) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public RemoteUserResponse registerPublicUser(RegisterRequest request) {
        try {
            return webClient.post()
                    .uri("/api/users/public/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RemoteUserResponse.class)
                    .block();
        } catch (WebClientResponseException.Conflict ex) {
            throw new UserAlreadyExistsException("Bu email adresi zaten kayıtlı.", ex);
        }
    }

    public Optional<RemoteUserResponse> findUserByEmail(String email) {
        try {
            return Optional.ofNullable(
                    webClient.get()
                            .uri("/api/users/by-email/{email}", email)
                            .retrieve()
                            .bodyToMono(RemoteUserResponse.class)
                            .block()
            );
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public Optional<InternalUserResponse> findInternalUserByEmail(String email) {
        try {
            return Optional.ofNullable(
                    webClient.get()
                            .uri("/api/users/internal/by-email/{email}", email)
                            .headers(headers -> headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION))))
                            .retrieve()
                            .bodyToMono(InternalUserResponse.class)
                            .block()
            );
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public void activateUser(Long userId) {
        webClient.post()
                .uri("/api/users/internal/{userId}/activate", userId)
                .headers(headers -> headers.addAll(internalHeaders()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void updatePassword(Long userId, String newPassword) {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        PasswordUpdateRequest body = new PasswordUpdateRequest(newPassword);
        webClient.post()
                .uri("/api/users/internal/{userId}/password", userId)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void updateLastLogin(Long userId) {
        webClient.post()
                .uri("/api/users/internal/{userId}/last-login", userId)
                .headers(headers -> headers.addAll(internalHeaders()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.getToken(USER_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION)));
        return headers;
    }

    private record PasswordUpdateRequest(String newPassword) {
    }
}

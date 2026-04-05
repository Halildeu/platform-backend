package com.example.auth.controller;

import com.example.auth.dto.LoginRequest;
import com.example.auth.dto.MessageResponse;
import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.ResetPasswordRequest;
import com.example.auth.dto.ForgotPasswordRequest;
import com.example.auth.dto.JwtResponse;
import com.example.auth.dto.v1.request.ForgotPasswordRequestDto;
import com.example.auth.dto.v1.request.LoginRequestDto;
import com.example.auth.dto.v1.request.RegisterRequestDto;
import com.example.auth.dto.v1.request.ResetPasswordRequestDto;
import com.example.auth.dto.v1.response.LoginResponseDto;
import com.example.auth.dto.v1.response.MessageDto;
import com.example.auth.dto.v1.response.RegisterResponseDto;
import com.example.auth.service.AuthService;
import com.example.auth.service.AuthService.RegistrationResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
// STORY-0033: Auth REST/DTO v1 Migration
public class AuthControllerV1 {

    private final AuthService authService;

    public AuthControllerV1(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<LoginResponseDto> createSession(@Valid @RequestBody LoginRequestDto request) {
        JwtResponse jwt = authService.login(request.getEmail(), request.getPassword(), request.getCompanyId());
        LoginResponseDto response = new LoginResponseDto(
                jwt.getToken(),
                jwt.getEmail(),
                jwt.getRole(),
                jwt.getPermissions(),
                jwt.getExpiresAt(),
                jwt.getSessionTimeoutMinutes()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/registrations")
    public ResponseEntity<RegisterResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        RegisterRequest legacy = new RegisterRequest();
        legacy.setName(request.getName());
        legacy.setEmail(request.getEmail());
        legacy.setPassword(request.getPassword());

        RegistrationResult result = authService.registerDetailed(legacy);
        RegisterResponseDto response = new RegisterResponseDto(
                result.userId(),
                result.email(),
                result.status(),
                result.message()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/password-resets")
    public ResponseEntity<MessageDto> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        ForgotPasswordRequest legacy = new ForgotPasswordRequest();
        legacy.setEmail(request.getEmail());
        MessageResponse response = authService.forgotPassword(legacy);
        return ResponseEntity.ok(new MessageDto(response.getMessage()));
    }

    @PostMapping("/password-resets/{token}")
    public ResponseEntity<MessageDto> resetPassword(@PathVariable("token") String token,
                                                    @Valid @RequestBody ResetPasswordRequestDto request) {
        ResetPasswordRequest legacy = new ResetPasswordRequest();
        legacy.setToken(token);
        legacy.setNewPassword(request.getNewPassword());
        MessageResponse response = authService.resetPassword(legacy);
        return ResponseEntity.ok(new MessageDto(response.getMessage()));
    }

    @PostMapping("/email-verifications/{token}")
    public ResponseEntity<MessageDto> verifyEmail(@PathVariable("token") String token) {
        MessageResponse response = authService.verifyEmail(token);
        return ResponseEntity.ok(new MessageDto(response.getMessage()));
    }
}

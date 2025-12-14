package com.example.auth.controller;

import com.example.auth.dto.ForgotPasswordRequest;
import com.example.auth.dto.JwtResponse;
import com.example.auth.dto.LoginRequest;
import com.example.auth.dto.MessageResponse;
import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.ResetPasswordRequest;
import com.example.auth.user.UserAlreadyExistsException;
import com.example.auth.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Profile({"local", "dev"})
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Deprecated
    public ResponseEntity<JwtResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        JwtResponse jwtResponse = authService.login(
                loginRequest.getEmail(),
                loginRequest.getPassword(),
                loginRequest.getCompanyId());
        return ResponseEntity.ok(jwtResponse);
    }

    @PostMapping("/register")
    @Deprecated
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        MessageResponse response = authService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/verify-email")
    @Deprecated
    public ResponseEntity<MessageResponse> verifyEmail(@RequestParam("token") String token) {
        MessageResponse response = authService.verifyEmail(token);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    @Deprecated
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        MessageResponse response = authService.forgotPassword(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reset-password")
    @Deprecated
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        MessageResponse response = authService.resetPassword(request);
        return ResponseEntity.ok(response);
    }
}

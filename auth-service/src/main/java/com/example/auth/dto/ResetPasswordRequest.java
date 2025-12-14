package com.example.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequest {

    @NotBlank(message = "Token alanı boş olamaz.")
    private String token;

    @NotBlank(message = "Yeni şifre boş olamaz.")
    @Size(min = 8, message = "Yeni şifre en az 8 karakter olmalıdır.")
    private String newPassword;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}

package com.example.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class InternalPasswordUpdateRequest {

    @NotBlank
    @Size(min = 8, message = "Şifre en az 8 karakter olmalıdır")
    private String newPassword;

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}

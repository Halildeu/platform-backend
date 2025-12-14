package com.example.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank(message = "İsim alanı boş olamaz.")
    private String name;

    @Email(message = "Geçerli bir email adresi girin.")
    @NotBlank(message = "Email alanı boş olamaz.")
    private String email;

    @NotBlank(message = "Şifre alanı boş olamaz.")
    @Size(min = 8, message = "Şifre en az 8 karakter olmalıdır.")
    private String password;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

package com.example.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @Email(message = "Geçerli bir email adresi girin.")
    @NotBlank(message = "Email alanı boş olamaz.")
    private String email;

    @NotBlank(message = "Şifre alanı boş olamaz.")
    private String password;
    private Long companyId;

    // Getter ve Setter'lar
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
}

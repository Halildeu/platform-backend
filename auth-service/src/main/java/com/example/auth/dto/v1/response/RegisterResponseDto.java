package com.example.auth.dto.v1.response;

public class RegisterResponseDto {
    private Long userId;
    private String email;
    private String status;
    private String message;

    public RegisterResponseDto() {
    }

    public RegisterResponseDto(Long userId, String email, String status, String message) {
        this.userId = userId;
        this.email = email;
        this.status = status;
        this.message = message;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

package com.example.permission.dto.v1;

/**
 * DTO for creating a new role. Accepts only the fields relevant to role creation.
 * STORY-0318: replaces reuse of CloneRoleRequestDto for the POST /roles endpoint.
 */
public record CreateRoleRequestDto(
    String name,
    String description
) {}

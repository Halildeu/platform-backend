package com.example.user.dto;

/**
 * One people-picker row: exactly what a person needs to pick an assignee and what the caller
 * needs to send back ({@code userId}). No role, company, status or Keycloak binding.
 */
public record AssigneeCandidateEntry(long userId, String name, String email) {
}

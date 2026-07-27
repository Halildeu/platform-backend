package com.example.user.dto;

/**
 * Faz 35 ES-203/C — one resolved subject.
 *
 * <p>{@code displayName} is {@code null} when the subject is unknown or the
 * row is soft-deleted; the two cases are indistinguishable by design, so the
 * endpoint cannot be used to probe whether an erased identity once existed.
 * Deliberately absent: email, role, enabled flag, numeric id — a name
 * resolver that leaks profile data stops being a name resolver.
 */
public record DisplayNameEntry(String subject, String displayName) {
}

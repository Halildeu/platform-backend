package com.example.schema.dto;

/**
 * Codex 019dda1c iter-29 + iter-30: typed payload for the master-data
 * internal read endpoint ({@code GET /api/v1/schema/master-data/{kind}}).
 *
 * <p>iter-30 added the optional {@code code} field — a human-friendly
 * identifier (PROJECT_NUMBER, COMPANY_SHORT_CODE, SPECIAL_CODE) shown
 * alongside the name in the drawer so admins can disambiguate rows that
 * share similar names. Null-safe; the drawer renders the code as a
 * subtle prefix when present and falls back to plain name otherwise.
 *
 * <p>Backed by direct SQL against the workcube_mikrolink MSSQL schema.
 */
public record MasterDataItemDto(Long id, String code, String name, boolean status) {}

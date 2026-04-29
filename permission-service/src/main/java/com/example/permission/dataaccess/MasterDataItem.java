package com.example.permission.dataaccess;

/**
 * Lightweight DTO for master data scope picker dropdowns.
 *
 * <p>iter-30 added an optional {@code code} field (PROJECT_NUMBER,
 * COMPANY_SHORT_CODE, SPECIAL_CODE) so the drawer can show a code prefix
 * alongside the name and the admin can disambiguate similarly-named rows.
 *
 * @param id     workcube source PK (numeric, e.g. COMP_ID, PROJECT_ID)
 * @param code   optional natural code (nullable; not every entity has one)
 * @param name   display name (Turkish locale, fallback empty string)
 * @param status active/inactive flag (true = aktif)
 */
public record MasterDataItem(Long id, String code, String name, boolean status) {

    /**
     * Backward-compat constructor for callers that haven't been updated to
     * supply a code (e.g. the legacy reports_db Postgres mirror service in
     * {@link MasterDataService}).
     */
    public MasterDataItem(Long id, String name, boolean status) {
        this(id, null, name, status);
    }
}

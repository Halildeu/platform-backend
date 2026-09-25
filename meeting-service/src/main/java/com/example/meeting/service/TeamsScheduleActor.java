package com.example.meeting.service;

import java.net.URI;
import java.util.UUID;

/** Persisted identity binding for a user-selected schedule; never contains a user token. */
public record TeamsScheduleActor(int version, String issuer, String subject, UUID organizationId, UUID microsoftTenantId, String authzPrincipal,
                                 long userId, long companyId) {
    public boolean isValid() {
        if (version != 1 || issuer == null || issuer.length() > 2048 || subject == null || subject.isBlank() || subject.length() > 200
                || subject.chars().anyMatch(Character::isISOControl) || organizationId == null || organizationId.equals(new UUID(0, 0))
                || microsoftTenantId == null || microsoftTenantId.equals(new UUID(0, 0))
                || userId <= 0 || companyId <= 0
                || (!subject.equals(authzPrincipal) && !Long.toString(userId).equals(authzPrincipal))) return false;
        try {
            URI uri = URI.create(issuer);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}

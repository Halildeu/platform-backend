package com.example.meeting.security;

public interface TenantContextResolver {

    AdminTenantContext resolveRequired();
}

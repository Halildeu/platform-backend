package com.example.transcript.security;

public interface TenantContextResolver {

    AdminTenantContext resolveRequired();
}

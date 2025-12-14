package com.example.user.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

public class ServiceAuthenticationToken extends AbstractAuthenticationToken {

    private final String serviceId;
    private final String environment;

    public ServiceAuthenticationToken(String serviceId,
                                      String environment,
                                      Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.serviceId = serviceId;
        this.environment = environment;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return serviceId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getEnvironment() {
        return environment;
    }
}

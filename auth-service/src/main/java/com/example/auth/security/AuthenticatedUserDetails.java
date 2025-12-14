package com.example.auth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

public class AuthenticatedUserDetails extends User {

    private final Long id;
    private final Integer sessionTimeoutMinutes;

    public AuthenticatedUserDetails(Long id,
                                    String username,
                                    String password,
                                    boolean enabled,
                                    Collection<? extends GrantedAuthority> authorities,
                                    Integer sessionTimeoutMinutes) {
        super(username, password, enabled, true, true, true, authorities);
        this.id = id;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public Long getId() {
        return id;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }
}

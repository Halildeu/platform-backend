package com.example.variant.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AuthenticatedUserPrincipal implements UserDetails {

    private final AuthenticatedUser user;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUserPrincipal(AuthenticatedUser user) {
        this.user = user;
        String role = user.role();
        String springRole = role != null && role.startsWith("ROLE_") ? role : "ROLE_" + (role == null ? "USER" : role);
        this.authorities = List.of(new SimpleGrantedAuthority(springRole.toUpperCase()));
    }

    public AuthenticatedUser user() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return user.email();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

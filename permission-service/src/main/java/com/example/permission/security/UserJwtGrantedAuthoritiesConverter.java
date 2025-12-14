package com.example.permission.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Jwt içindeki role/permissions claim'lerini Spring Security yetkilerine çevirir.
 */
public class UserJwtGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        String role = jwt.getClaimAsString("role");
        if (StringUtils.hasText(role)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(Locale.ROOT)));
        }

        List<String> perms = jwt.getClaimAsStringList("permissions");
        if (perms != null) {
            for (String perm : perms) {
                if (StringUtils.hasText(perm)) {
                    authorities.add(new SimpleGrantedAuthority("PERM_" + perm.trim().toUpperCase(Locale.ROOT)));
                }
            }
        }

        return authorities;
    }
}

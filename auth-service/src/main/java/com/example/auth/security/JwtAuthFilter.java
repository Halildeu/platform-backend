package com.example.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile({"local", "dev"})
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    public JwtAuthFilter(JwtTokenProvider tokenProvider, UserDetailsService userDetailsService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                      @NonNull HttpServletResponse response,
                                      @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        
        // 1. Login/Register gibi halka açık yolları filtrelemeden pas geç
        if (request.getServletPath().contains("/api/auth")) {
            filterChain.doFilter(request, response);
            return; // Metodun geri kalanını çalıştırma
        }

        try {
            // 2. İstekten JWT'yi al
            String jwt = getJwtFromRequest(request);

            // 3. Token mevcut ve geçerli mi diye kontrol et
            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                // 4. Token'dan kullanıcı adını (email) al
                String username = tokenProvider.getUsernameFromToken(jwt);
                
                // 5. Veritabanından kullanıcıyı UserDetailsService ile yükle
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                
                // 6. Kullanıcı için bir Authentication nesnesi oluştur
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 7. Oluşturulan Authentication nesnesini SecurityContext'e yerleştir.
                // Bu, isteğin geri kalanında kullanıcının kimliğinin doğrulanmış olduğunu belirtir.
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            // Hata durumunda loglama yapılabilir.
            // logger.error("Kullanıcı kimliği ayarlanamadı: {}", ex);
        }

        // 8. Filtre zincirinde bir sonraki filtreye devam et
        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

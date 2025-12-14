package com.example.user.security; // user-service içindeki paket yolu

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile({"local", "dev"})
@ConditionalOnBean(JwtTokenProvider.class)
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
        
        // Bu kontrol auth-service'e özgüdür ama burada bir zararı olmaz.
        if (request.getServletPath().contains("/api/auth")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // İstekten JWT'yi al
            String jwt = getJwtFromRequest(request);

            // Token mevcut ve geçerli mi diye kontrol et
            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                // Token'dan kullanıcı adını (email) al
                String username = tokenProvider.getUsernameFromToken(jwt);
                
                // Veritabanından kullanıcıyı UserDetailsService ile yükle
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                
                // YENİ EKLENEN LOGLAMA KODU
                // Bu loglar, Spring Security'nin kullanıcıyı ve yetkilerini nasıl gördüğünü bize gösterecek.
                System.out.println(">>> JwtAuthFilter: Kullanıcı bulundu: " + userDetails.getUsername());
                System.out.println(">>> JwtAuthFilter: Kullanıcının Yetkileri: " + userDetails.getAuthorities());
                
                // Kullanıcı için bir Authentication nesnesi oluştur
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // Oluşturulan Authentication nesnesini SecurityContext'e yerleştir.
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            // Hata durumunda loglama yapılabilir.
            // logger.error("Kullanıcı kimliği ayarlanamadı: {}", ex);
        }

        // Filtre zincirinde bir sonraki filtreye devam et
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

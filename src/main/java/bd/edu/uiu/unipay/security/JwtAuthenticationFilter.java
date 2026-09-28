package bd.edu.uiu.unipay.security;

import bd.edu.uiu.unipay.user.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stateless JWT authentication filter — reads {@code Authorization: Bearer <jwt>},
 * verifies the signature and installs the authenticated principal for the request.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            jwtService.verifyToken(token)
                    .map(Claims::getSubject)
                    .ifPresent(userId -> {
                        try {
                            UserDetails principal = userDetailsService.loadUserByUsername(userId);
                            var authentication = new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities());
                            authentication.setDetails(
                                    new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        } catch (Exception ignored) {
                            // unknown subject — leave the request unauthenticated
                        }
                    });
        }

        filterChain.doFilter(request, response);
    }
}

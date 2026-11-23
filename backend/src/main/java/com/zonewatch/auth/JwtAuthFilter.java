package com.zonewatch.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless bearer authentication. The signed token carries username and role, so the ingest
 * path does not need a database read per request.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public JwtAuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.verify(header.substring(7).trim()).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority(user.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("user", user.username());
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("user");
        }
    }
}

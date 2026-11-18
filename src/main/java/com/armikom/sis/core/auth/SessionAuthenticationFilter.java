package com.armikom.sis.core.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code sis_session} cookie into a Spring Security {@code Authentication} whose principal is a
 * {@link SisPrincipal}. Authorities: {@code ROLE_USER}, plus {@code ROLE_ADMIN} for system administrators.
 * Only API/actuator requests are looked up, static files never touch the database.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final UserSessionService sessions;

    public SessionAuthenticationFilter(UserSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/") || uri.startsWith("/actuator/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = sessions.tokenOf(request);
        if (token != null) {
            sessions.principalFor(token).ifPresent(p -> {
                List<GrantedAuthority> authorities = new ArrayList<>(2);
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                if (p.systemAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, authorities));
                SecurityContextHolder.setContext(ctx);
            });
        }
        chain.doFilter(request, response);
    }
}

package com.armikom.sis.security;

import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.auth.SessionAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
public class SecurityConfig {

    static final String CSP = "default-src 'self'; img-src 'self' blob: data:; worker-src 'self' blob:; "
            + "script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self'";

    /** Delegating encoder: existing {bcrypt} hashes from yoklama keep working. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // The filters are added to the security chain explicitly; keep Boot from also registering them
    // as plain servlet filters.
    @Bean
    public FilterRegistrationBean<DeviceCookieFilter> deviceCookieFilterRegistration(DeviceCookieFilter f) {
        FilterRegistrationBean<DeviceCookieFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter f) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthFilterRegistration(SessionAuthenticationFilter f) {
        FilterRegistrationBean<SessionAuthenticationFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DeviceCookieFilter deviceCookieFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   SessionAuthenticationFilter sessionAuthFilter) throws Exception {
        http
            // Sign-in state lives in the user_session table (sis_session cookie), never in an HttpSession.
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .csrf(csrf -> csrf
                .spa()
                .ignoringRequestMatchers("/api/public/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**", "/api/auth/login", "/api/auth/csrf", "/api/auth/request",
                        "/api/auth/verify", "/api/auth/logout", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**", "/actuator/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> JsonErrors.write(res, ErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) -> JsonErrors.write(res, ErrorCode.FORBIDDEN)))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(deviceCookieFilter, RateLimitFilter.class)
            .addFilterBefore(sessionAuthFilter, AnonymousAuthenticationFilter.class)
            .headers(h -> {
                // Cache-Control is owned by SpaWebConfig's filter (immutable bundles, no-cache shell, no-store API).
                h.cacheControl(cc -> cc.disable());
                h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP));
                h.referrerPolicy(rp -> rp.policy(ReferrerPolicy.SAME_ORIGIN));
                h.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(self), geolocation=(self), bluetooth=(self)"));
            });
        return http.build();
    }
}

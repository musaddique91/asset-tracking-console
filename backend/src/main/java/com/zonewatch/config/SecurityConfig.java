package com.zonewatch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonewatch.auth.JwtAuthFilter;
import com.zonewatch.common.ProblemCode;
import com.zonewatch.common.Problems;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Role rules of the contract: INGEST feeds, OPERATOR and SUPERVISOR read and work alerts. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthFilter jwt, ObjectMapper mapper) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/error").permitAll()
                        // management endpoints are not routed by the load balancer; reachable inside the network only
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/reports/batch").hasRole("INGEST")
                        .requestMatchers(HttpMethod.GET, "/api/v1/stats").authenticated()
                        .requestMatchers("/api/v1/rejections/**").hasAnyRole("INGEST", "SUPERVISOR")
                        .requestMatchers("/api/v1/ops/**").hasRole("SUPERVISOR")
                        .requestMatchers("/api/v1/assets/**", "/api/v1/alerts/**", "/api/v1/zones/**", "/api/v1/stream")
                        .hasAnyRole("OPERATOR", "SUPERVISOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                Problems.write(res, mapper, ProblemCode.UNAUTHENTICATED, "a valid bearer token is required", req))
                        .accessDeniedHandler((req, res, ex) ->
                                Problems.write(res, mapper, ProblemCode.FORBIDDEN, "your role may not call this operation", req)))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** The JWT filter runs inside the security chain only, not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

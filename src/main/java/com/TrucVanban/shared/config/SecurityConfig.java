package com.TrucVanban.shared.config;

import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableAsync
public class SecurityConfig {

    @Bean
    public SignatureVerificationFilter signatureVerificationFilter(
            RegistryService registryService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            CanonicalStringBuilder canonicalStringBuilder) {

        // Truyền canonicalStringBuilder vào constructor của Filter
        return new SignatureVerificationFilter(registryService, auditLogService, objectMapper, canonicalStringBuilder);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SignatureVerificationFilter signatureVerificationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Cho phép truy cập hoàn toàn vào Swagger UI và API Docs
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api/public/**"
                        ).permitAll()
                        .anyRequest().permitAll() // sửa sau khi đã có authentication
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Đăng ký SignatureVerificationFilter trước UsernamePasswordAuthenticationFilter
                .addFilterBefore(signatureVerificationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
package com.TrucVanban.shared.config;

import com.TrucVanban.auth.enums.UserStatus;
import com.TrucVanban.auth.repository.UserRepository;
import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.security.filter.SignatureVerificationFilter;
import com.TrucVanban.shared.security.hmac.HmacAuthenticationFilter;
import com.TrucVanban.shared.security.hmac.HmacAuthenticationService;
import com.TrucVanban.shared.security.hmac.HmacProperties;
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public SignatureVerificationFilter signatureVerificationFilter(
            RegistryService registryService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            CanonicalStringBuilder canonicalStringBuilder) {

        return new SignatureVerificationFilter(registryService, auditLogService, objectMapper, canonicalStringBuilder);
    }

    @Bean
    public HmacAuthenticationFilter hmacAuthenticationFilter(
            HmacAuthenticationService hmacAuthenticationService,
            HmacProperties hmacProperties,
            AuditLogService auditLogService) {
        return new HmacAuthenticationFilter(hmacAuthenticationService, hmacProperties, auditLogService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SignatureVerificationFilter signatureVerificationFilter,
                                                   HmacAuthenticationFilter hmacAuthenticationFilter,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/auth/login",
                                "/auth/refresh",
                                "/simulator/**",
                                "/registry/**", //temp
                                "/mock/**",
                                "/exchange",
                                "/exchange-documents/signatures"  // Đa chữ ký — xác thực qua Filter 2 tầng
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAfter(signatureVerificationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(hmacAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
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

    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] bytes = java.util.Base64.getDecoder().decode(jwtSecret);
        SecretKeySpec secretKey = new SecretKeySpec(bytes, "HmacSHA256");
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();

        return jwtDecoder;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        byte[] bytes = java.util.Base64.getDecoder().decode(jwtSecret);
        return new NimbusJwtEncoder(new ImmutableSecret<>(bytes));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(StringRedisTemplate redisTemplate, UserRepository userRepository) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String tokenValue = jwt.getTokenValue();
            if (redisTemplate.hasKey("blacklist:" + tokenValue)) {
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("Token bị thu hồi hoặc không hợp lệ");
            }
            String username = jwt.getSubject();
            if (!userRepository.existsByUsernameAndStatus(username, UserStatus.ACTIVE)) {
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("Người dùng bị khóa hoặc không tồn tại");
            }

            String type = jwt.getClaimAsString("type");
            if (!"ACCESS".equals(type)) {
                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("Chỉ Access Token mới được phép truy cập API");
            }

            Collection<String> roles = jwt.getClaimAsStringList("roles");
            Collection<String> permissions = jwt.getClaimAsStringList("permissions");

            Stream<String> rolesStream = roles != null ? roles.stream() : Stream.empty();
            Stream<String> permissionsStream = permissions != null ? permissions.stream() : Stream.empty();
            
            return Stream.concat(rolesStream, permissionsStream)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        });
        return converter;
    }


    //Băm password
    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    }

    @Bean
    public org.springframework.security.authentication.AuthenticationManager authenticationManager(
            org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}

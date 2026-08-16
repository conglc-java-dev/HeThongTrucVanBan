package com.TrucVanban.auth.service.impl;

import com.TrucVanban.auth.dto.request.LoginRequest;
import com.TrucVanban.auth.dto.request.RefreshTokenRequest;
import com.TrucVanban.auth.dto.response.TokenResponse;
import com.TrucVanban.auth.entity.RefreshToken;
import com.TrucVanban.auth.repository.RefreshTokenRepository;
import com.TrucVanban.auth.service.AuthService;
import com.TrucVanban.auth.service.CustomUserDetailsService;
import com.TrucVanban.auth.service.JwtTokenService;
import com.TrucVanban.shared.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtDecoder jwtDecoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StringRedisTemplate redisTemplate;
    private final JwtTokenService jwtTokenService;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );
            CustomUserDetailsService.CustomUserDetails userDetails = (CustomUserDetailsService.CustomUserDetails) authentication.getPrincipal();
            return jwtTokenService.generateTokens(userDetails);
        } catch (org.springframework.security.core.AuthenticationException e) {
            throw new UnauthorizedException("Tên đăng nhập hoặc mật khẩu không hợp lệ");
        }
    }

    @Override
    @Transactional
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new UnauthorizedException("Refresh token không hợp lệ"));

        if (storedToken.isRevoked()) {
            throw new UnauthorizedException("Refresh token đã bị thu hồi");
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Refresh token đã hết hạn");
        }

        try {
            jwtDecoder.decode(request.getRefreshToken());
            CustomUserDetailsService.CustomUserDetails userDetails = customUserDetailsService.loadUserById(storedToken.getUserId());

            // Revoke the old token (Token Rotation) to prevent reuse
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);

            return jwtTokenService.generateTokens(userDetails);
        } catch (Exception e) {
            throw new UnauthorizedException("Refresh token không hợp lệ hoặc đã hết hạn");
        }
    }

    @Override
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            try {
                Jwt jwt = jwtDecoder.decode(accessToken);
                Instant expiresAt = jwt.getExpiresAt();
                if (expiresAt != null) {
                    long ttl = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
                    if (ttl > 0) {
                        redisTemplate.opsForValue().set(
                                "blacklist:" + accessToken, 
                                "true", 
                                ttl, 
                                java.util.concurrent.TimeUnit.SECONDS
                        );
                    }
                }
            } catch (JwtException e) {
                // Ignore invalid or expired token
            }
        }
        
        if (refreshToken != null) {
            refreshTokenRepository.findByToken(refreshToken).ifPresent(storedToken -> {
                storedToken.setRevoked(true);
                refreshTokenRepository.save(storedToken);
            });
        }
    }

}

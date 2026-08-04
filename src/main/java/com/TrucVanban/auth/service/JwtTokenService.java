package com.TrucVanban.auth.service;

import com.TrucVanban.auth.dto.response.TokenResponse;
import com.TrucVanban.auth.entity.RefreshToken;
import com.TrucVanban.auth.entity.User;
import com.TrucVanban.auth.repository.RefreshTokenRepository;
import com.TrucVanban.auth.service.CustomUserDetailsService.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.access-token-expiration:3600}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration:604800}")
    private long refreshTokenExpiration;

    public TokenResponse generateTokens(CustomUserDetails userDetails) {
        Instant now = Instant.now();
        User user = userDetails.getUser();

        List<String> roles = userDetails.getRoles();
        List<String> permissions = userDetails.getPermissions();

        JwtClaimsSet accessClaims = JwtClaimsSet.builder()
                .issuer("HeThongTrucVanBan")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTokenExpiration))
                .subject(user.getUsername())
                .claim("type", "ACCESS")
                .claim("roles", roles)
                .claim("permissions", permissions)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, accessClaims)).getTokenValue();

        JwtClaimsSet refreshClaims = JwtClaimsSet.builder()
                .issuer("HeThongTrucVanBan")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(refreshTokenExpiration))
                .subject(user.getUsername())
                .claim("type", "REFRESH")
                .build();

        String refreshToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, refreshClaims)).getTokenValue();

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .token(refreshToken)
                .userId(user.getId())
                .expiresAt(now.plusSeconds(refreshTokenExpiration))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}

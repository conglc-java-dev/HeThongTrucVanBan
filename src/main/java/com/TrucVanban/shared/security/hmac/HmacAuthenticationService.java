package com.TrucVanban.shared.security.hmac;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class HmacAuthenticationService {

    private final ApiKeyCacheService apiKeyCacheService;
    private final NonceStore nonceStore;
    private final SignatureCalculator signatureCalculator;
    private final HmacProperties hmacProperties;

    public void authenticate(HttpServletRequest request) {
        String apiKey = request.getHeader(hmacProperties.getHeader().getApiKey());
        String timestamp = request.getHeader(hmacProperties.getHeader().getTimestamp());
        String nonce = request.getHeader(hmacProperties.getHeader().getNonce());
        String signatureHeader = request.getHeader(hmacProperties.getHeader().getSignature());

        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signatureHeader)) {
            throw new HmacAuthenticationException.MissingAuthHeaderException("Thiếu các header xác thực HMAC bắt buộc");
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new HmacAuthenticationException.TimestampSkewException("Định dạng timestamp không hợp lệ");
        }

        long now = OffsetDateTime.now().toEpochSecond();
        long drift = Math.abs(now - timestampSeconds);
        if (drift > hmacProperties.getClockSkew().getSeconds()) {
            throw new HmacAuthenticationException.TimestampSkewException("Độ lệch thời gian vượt quá giới hạn cho phép");
        }

        ApiKeyCacheValue apiKeyCacheValue = apiKeyCacheService.getApiKey(apiKey);
        if (apiKeyCacheValue == null) {
            throw new HmacAuthenticationException.ApiKeyInvalidException("API key không hợp lệ hoặc không hoạt động");
        }

        if (apiKeyCacheValue.expiresAt() != null && OffsetDateTime.now().isAfter(apiKeyCacheValue.expiresAt())) {
            throw new HmacAuthenticationException.ApiKeyExpiredException("API key đã hết hạn");
        }

        if (!"ACTIVE".equals(apiKeyCacheValue.keyStatus()) || !"ACTIVE".equals(apiKeyCacheValue.agencyStatus())) {
            throw new HmacAuthenticationException.AgencyInactiveException("Cơ quan hoặc API key không hoạt động");
        }

        byte[] body = readRequestBody(request);
        String canonicalString = signatureCalculator.calculateCanonicalString(
                request.getMethod(),
                request.getRequestURI(),
                request.getParameterMap(),
                apiKey,
                timestamp,
                nonce,
                body
        );

        String normalizedSignature = normalizeSignature(signatureHeader);
        String expectedSignature = signatureCalculator.calculateSignature(apiKeyCacheValue.secret(), canonicalString);

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), normalizedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new HmacAuthenticationException.SignatureInvalidException("Chữ ký không khớp");
        }

        if (!nonceStore.reserveNonce(apiKey, nonce, hmacProperties.getNonceTtl())) {
            throw new HmacAuthenticationException.ReplayDetectedException("Phát hiện yêu cầu trùng lặp");
        }

        request.setAttribute("verified_org_id", apiKeyCacheValue.agencyId());
        request.setAttribute("verified_org_code", apiKeyCacheValue.agencyCode());
    }

    private String normalizeSignature(String signatureHeader) {
        if (signatureHeader == null) {
            return "";
        }
        String trimmed = signatureHeader.trim();
        if (trimmed.startsWith("v1=") || trimmed.startsWith("V1=")) {
            return trimmed.substring(trimmed.indexOf('=') + 1);
        }
        return trimmed;
    }

    private byte[] readRequestBody(HttpServletRequest request) {
        try {
            // Nếu đã wrap bởi CachedBodyRequestWrapper, lấy body từ cache (tránh đọc stream 2 lần)
            if (request instanceof CachedBodyRequestWrapper cached) {
                return cached.getCachedBody();
            }
            long contentLength = request.getContentLengthLong();
            if (contentLength > 0 && contentLength > hmacProperties.getMaxBodySize()) {
                throw new IllegalStateException("Kích thước body vượt quá giới hạn cho phép");
            }
            return request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc request body để xác thực HMAC", e);
        }
    }
}

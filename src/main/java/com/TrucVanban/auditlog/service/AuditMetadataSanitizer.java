package com.TrucVanban.auditlog.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AuditMetadataSanitizer {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "accesstoken", "refreshtoken", "secret", "apikey",
            "signature", "signaturevalue", "transportsignature", "canonicalstring",
            "privatekey", "presignedurl", "authorization", "cookie"
    );

    public Map<String, Object> sanitize(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!isSensitive(key)) result.put(key, sanitizeValue(value));
        });
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (!(value instanceof Map<?, ?> nested)) return value;
        Map<String, Object> normalized = new LinkedHashMap<>();
        nested.forEach((key, nestedValue) -> normalized.put(String.valueOf(key), nestedValue));
        return sanitize(normalized);
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized);
    }
}

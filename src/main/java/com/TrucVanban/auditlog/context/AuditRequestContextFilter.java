package com.TrucVanban.auditlog.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = validUuid(request.getHeader("X-Request-Id"));
        if (requestId == null) requestId = UUID.randomUUID().toString();

        String correlationId = validUuid(request.getHeader("X-Correlation-Id"));
        if (correlationId == null) correlationId = requestId;

        request.setAttribute(AuditRequestContextProvider.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(AuditRequestContextProvider.CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader("X-Request-Id", requestId);
        filterChain.doFilter(request, response);
    }

    private String validUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}


package com.TrucVanban.auditlog.context;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class AuditRequestContextProvider {

    public static final String REQUEST_ID_ATTRIBUTE = "audit.requestId";
    public static final String CORRELATION_ID_ATTRIBUTE = "audit.correlationId";

    public AuditRequestContext getCurrentContext() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return AuditRequestContext.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        return new AuditRequestContext(
                getAttribute(request, REQUEST_ID_ATTRIBUTE),
                getAttribute(request, CORRELATION_ID_ATTRIBUTE),
                request.getRemoteAddr(),
                truncate(request.getHeader("User-Agent"), 500)
        );
    }

    private String getAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value != null ? value.toString() : null;
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}


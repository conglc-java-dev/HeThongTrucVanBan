package com.TrucVanban.auditlog.resolver;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditResolverRegistry {
    private final ApplicationContext applicationContext;

    public AuditEventResolver get(Class<? extends AuditEventResolver> resolverType) {
        return applicationContext.getBean(resolverType);
    }
}


package com.TrucVanban.auditlog.aspect;

import com.TrucVanban.auditlog.annotation.Audited;
import com.TrucVanban.auditlog.domain.AuditInvocation;
import com.TrucVanban.auditlog.domain.AuditTransactionMode;
import com.TrucVanban.auditlog.resolver.AuditEventResolver;
import com.TrucVanban.auditlog.resolver.AuditResolverRegistry;
import com.TrucVanban.auditlog.service.AuditWriter;
import com.TrucVanban.auditlog.service.IndependentAuditWriter;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class AuditAspect {
    private final AuditResolverRegistry resolverRegistry;
    private final AuditWriter auditWriter;
    private final IndependentAuditWriter independentAuditWriter;

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        AuditEventResolver resolver = resolverRegistry.get(audited.resolver());
        Object beforeSnapshot = resolver.captureBefore(joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            AuditInvocation invocation = new AuditInvocation(
                    audited.operation(), joinPoint.getArgs(), result, null, beforeSnapshot);
            if (audited.transactionMode() == AuditTransactionMode.REQUIRED_CURRENT) {
                auditWriter.write(resolver.resolveSuccess(invocation));
            } else {
                independentAuditWriter.write(resolver.resolveSuccess(invocation));
            }
            return result;
        } catch (Throwable error) {
            AuditInvocation invocation = new AuditInvocation(
                    audited.operation(), joinPoint.getArgs(), null, error, beforeSnapshot);
            try {
                independentAuditWriter.write(resolver.resolveFailure(invocation));
            } catch (RuntimeException auditError) {
                error.addSuppressed(auditError);
            }
            throw error;
        }
    }
}


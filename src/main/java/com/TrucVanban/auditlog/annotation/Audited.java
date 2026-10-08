package com.TrucVanban.auditlog.annotation;

import com.TrucVanban.auditlog.domain.AuditOperation;
import com.TrucVanban.auditlog.domain.AuditTransactionMode;
import com.TrucVanban.auditlog.resolver.AuditEventResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    AuditOperation operation();
    Class<? extends AuditEventResolver> resolver();
    AuditTransactionMode transactionMode() default AuditTransactionMode.REQUIRED_CURRENT;
}


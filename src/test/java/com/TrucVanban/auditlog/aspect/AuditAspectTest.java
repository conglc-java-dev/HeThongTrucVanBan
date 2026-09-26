package com.TrucVanban.auditlog.aspect;

import com.TrucVanban.auditlog.annotation.Audited;
import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditOperation;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditTransactionMode;
import com.TrucVanban.auditlog.resolver.AuditEventResolver;
import com.TrucVanban.auditlog.resolver.AuditResolverRegistry;
import com.TrucVanban.auditlog.resolver.ExchangeDocumentAuditResolver;
import com.TrucVanban.auditlog.service.AuditWriter;
import com.TrucVanban.auditlog.service.IndependentAuditWriter;
import com.TrucVanban.shared.exception.BusinessLogicException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAspectTest {
    @Mock private AuditResolverRegistry resolverRegistry;
    @Mock private AuditWriter auditWriter;
    @Mock private IndependentAuditWriter independentAuditWriter;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Audited audited;
    @Mock private AuditEventResolver resolver;

    private AuditAspect aspect;
    private AuditEventCommand event;

    @BeforeEach
    void setUp() {
        aspect = new AuditAspect(resolverRegistry, auditWriter, independentAuditWriter);
        event = AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_UPDATED)
                .outcome(AuditOutcome.SUCCESS)
                .build();
        doReturn(ExchangeDocumentAuditResolver.class).when(audited).resolver();
        when(audited.operation()).thenReturn(AuditOperation.UPDATE_DOCUMENT);
        when(resolverRegistry.get(ExchangeDocumentAuditResolver.class)).thenReturn(resolver);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"DOC-001"});
    }

    @Test
    void writesSuccessfulEventInCurrentTransaction() throws Throwable {
        Object expectedResult = new Object();
        when(audited.transactionMode()).thenReturn(AuditTransactionMode.REQUIRED_CURRENT);
        when(joinPoint.proceed()).thenReturn(expectedResult);
        when(resolver.resolveSuccess(any())).thenReturn(List.of(event));

        Object actualResult = aspect.audit(joinPoint, audited);

        assertThat(actualResult).isSameAs(expectedResult);
        verify(auditWriter).write(List.of(event));
        verify(independentAuditWriter, never()).write(any());
    }

    @Test
    void writesFailureIndependentlyAndRethrowsOriginalError() throws Throwable {
        BusinessLogicException businessError = new BusinessLogicException("Không hợp lệ");
        AuditEventCommand failedEvent = AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_UPDATED)
                .outcome(AuditOutcome.REJECTED)
                .errorCode("BUSINESS_RULE_VIOLATION")
                .build();
        when(joinPoint.proceed()).thenThrow(businessError);
        when(resolver.resolveFailure(any())).thenReturn(List.of(failedEvent));

        assertThatThrownBy(() -> aspect.audit(joinPoint, audited))
                .isSameAs(businessError);
        verify(independentAuditWriter).write(List.of(failedEvent));
        verify(auditWriter, never()).write(any());
    }
}

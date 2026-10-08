package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditInvocation;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditVisibilityScope;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import com.TrucVanban.infrastructure.security.hmac.SignatureVerificationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MultiSignatureValidationAuditResolver implements AuditEventResolver {
    private static final String GATEWAY_ACTOR_ID = "GATEWAY";

    private final AuditErrorClassifier errorClassifier;

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        List<SignatureRequest> signatures = signatures(invocation);
        @SuppressWarnings("unchecked")
        List<SignatureVerificationResult> results = (List<SignatureVerificationResult>) invocation.result();
        boolean valid = results.stream().allMatch(SignatureVerificationResult::isValid);
        String signerCode = lastSignerCode(signatures);
        String documentCode = documentCode(invocation);

        AuditEventCommand.AuditEventCommandBuilder builder = AuditEventCommand.builder()
                .action(AuditAction.MULTI_SIGNATURE_VALIDATED)
                .outcome(valid ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                .actorType("SYSTEM")
                .actorId(GATEWAY_ACTOR_ID)
                .documentCode(documentCode)
                .metadata(Map.of(
                        "signatureCount", signatures.size(),
                        "verifiedCount", results.size(),
                        "signerCode", signerCode))
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY);
        if (!valid) {
            builder.errorCode("SIGNATURE_INVALID")
                    .errorMessage("Có chữ ký tài liệu không hợp lệ");
        }
        return List.of(builder.build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        List<SignatureRequest> signatures = signatures(invocation);
        AuditError error = errorClassifier.classify(invocation.error());
        String signerCode = lastSignerCode(signatures);
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.MULTI_SIGNATURE_VALIDATED)
                .outcome(error.outcome())
                .actorType("SYSTEM")
                .actorId(GATEWAY_ACTOR_ID)
                .documentCode(documentCode(invocation))
                .metadata(Map.of("signatureCount", signatures.size(), "signerCode", signerCode))
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }

    @SuppressWarnings("unchecked")
    private List<SignatureRequest> signatures(AuditInvocation invocation) {
        List<SignatureRequest> value = (List<SignatureRequest>) invocation.arguments()[1];
        return value != null ? value : List.of();
    }

    private String documentCode(AuditInvocation invocation) {
        return (String) invocation.arguments()[2];
    }

    private String lastSignerCode(List<SignatureRequest> signatures) {
        return signatures.stream()
                .max((left, right) -> Integer.compare(order(left), order(right)))
                .map(SignatureRequest::getSignerCode)
                .orElse("UNKNOWN");
    }

    private int order(SignatureRequest signature) {
        return signature.getSignatureOrder() != null ? signature.getSignatureOrder() : 0;
    }
}

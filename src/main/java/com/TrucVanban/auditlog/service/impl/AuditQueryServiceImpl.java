package com.TrucVanban.auditlog.service.impl;

import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditVisibilityScope;
import com.TrucVanban.auditlog.dto.request.AuditLogFilterRequest;
import com.TrucVanban.auditlog.dto.response.AuditLogPageResponse;
import com.TrucVanban.auditlog.dto.response.AuditLogResponse;
import com.TrucVanban.auditlog.dto.response.AuditPageableResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelineItemResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelinePageResponse;
import com.TrucVanban.auditlog.entity.AuditLog;
import com.TrucVanban.auditlog.repository.AuditLogRepository;
import com.TrucVanban.auditlog.service.AuditQueryService;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.DocumentSignature;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.enums.SigningFlowStatus;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.DocumentSignatureRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.exception.InvalidInputException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditQueryServiceImpl implements AuditQueryService {
    private static final String GATEWAY_ACTOR_NAME = "Cổng kết nối liên thông";
    private static final String UNKNOWN_ACTOR_NAME = "Không xác định";
    private static final List<String> ALL_VISIBILITY_SCOPES = List.of(
            AuditVisibilityScope.TRANSACTION_PARTICIPANTS.name(),
            AuditVisibilityScope.GATEWAY_ONLY.name());

    private final AuditLogRepository repository;
    private final RegistryService registryService;
    private final DocumentRepository documentRepository;
    private final ExchangeTransactionsRepository transactionRepository;
    private final DocumentSignatureRepository signatureRepository;

    @Override
    public AuditLogPageResponse search(AuditLogFilterRequest filter) {
        validateAccess();
        validateTimeRange(filter);
        String action = normalizeAction(filter.getAction());
        String result = normalizeResult(filter.getResult());
        PageRequest pageable = PageRequest.of(
                filter.getPage(),
                filter.getSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<AuditLogResponse> resultPage = repository.search(
                        ALL_VISIBILITY_SCOPES,
                        trimToNull(filter.getDocumentCode()),
                        trimToNull(filter.getTransactionCode()),
                        trimToNull(filter.getCorrelationId()),
                        action,
                        result,
                        filter.getFrom(),
                        filter.getTo(),
                        pageable)
                .map(this::toResponse);

        return new AuditLogPageResponse(
                resultPage.getContent(),
                new AuditPageableResponse(resultPage.getNumber(), resultPage.getSize()),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.isFirst(),
                resultPage.isLast());
    }

    @Override
    public AuditTimelinePageResponse getTimeline(String documentCode, int page, int size) {
        validateAccess();
        String normalizedDocumentCode = trimToNull(documentCode);
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditLog> auditPage = normalizedDocumentCode == null
                ? repository.findByVisibilityScopeIn(ALL_VISIBILITY_SCOPES, pageable)
                : repository.findDocumentTimeline(normalizedDocumentCode, ALL_VISIBILITY_SCOPES, pageable);

        TimelineSource source = normalizedDocumentCode != null
                ? loadTimelineSource(normalizedDocumentCode)
                : null;
        Set<String> organizationCodes = new LinkedHashSet<>();
        Set<Long> organizationIds = new LinkedHashSet<>();
        collectAuditOrganizations(auditPage.getContent(), organizationCodes, organizationIds);
        collectFlowOrganizations(source, organizationCodes, organizationIds);

        Map<String, String> organizationNamesByCode = registryService
                .getOrganizationNamesByCodes(organizationCodes);
        Map<Long, String> organizationNamesById = registryService
                .getOrganizationNamesByIds(organizationIds);
        List<AuditTimelineItemResponse> items = auditPage.stream()
                .map(audit -> toTimelineItem(audit, organizationNamesById, organizationNamesByCode))
                .toList();

        if (source == null) {
            return timelinePage(null, null, null, null, null, null, items, auditPage);
        }

        ExchangeTransactions signingTransaction = source.signingTransaction();
        List<AuditTimelinePageResponse.SigningOrganization> signingOrganizations =
                buildSigningOrganizations(source, organizationNamesByCode, organizationNamesById);
        List<String> receiverOrganizations = receiverOrganizations(
                source, organizationNamesByCode, organizationNamesById);
        String flowStatus = signingTransaction != null && signingTransaction.getSigningFlowStatus() != null
                ? signingTransaction.getSigningFlowStatus().name()
                : null;

        return timelinePage(
                normalizedDocumentCode,
                organizationName(source.document().getSenderOrgId(), organizationNamesById),
                flowStatus,
                currentSigningOrganization(source, organizationNamesByCode),
                signingOrganizations,
                receiverOrganizations,
                items,
                auditPage);
    }

    private AuditTimelinePageResponse timelinePage(
            String documentCode,
            String senderOrganization,
            String signingFlowStatus,
            String currentSigningOrganization,
            List<AuditTimelinePageResponse.SigningOrganization> signingOrganizations,
            List<String> receiverOrganizations,
            List<AuditTimelineItemResponse> items,
            Page<AuditLog> auditPage) {
        return new AuditTimelinePageResponse(
                documentCode,
                senderOrganization,
                signingFlowStatus,
                currentSigningOrganization,
                signingOrganizations,
                receiverOrganizations,
                items,
                auditPage.getNumber(),
                auditPage.getSize(),
                auditPage.getTotalElements(),
                auditPage.getTotalPages());
    }

    private TimelineSource loadTimelineSource(String documentCode) {
        Document document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy văn bản với mã: " + documentCode));
        List<ExchangeTransactions> transactions = transactionRepository.findByDocumentId(document.getId());
        ExchangeTransactions signingTransaction = transactions.stream()
                .filter(transaction -> !jsonCodes(transaction.getRoutingList()).isEmpty())
                .findFirst()
                .orElse(null);
        List<String> routingCodes = signingTransaction != null
                ? jsonCodes(signingTransaction.getRoutingList())
                : List.of();
        List<String> distributionCodes = signingTransaction != null
                ? jsonCodes(signingTransaction.getDistributionList())
                : List.of();
        Set<String> signedCodes = signingTransaction != null
                ? signatureRepository.findByTransactionIdOrderBySignatureOrderAsc(signingTransaction.getId()).stream()
                        .map(DocumentSignature::getSignerCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        return new TimelineSource(
                document, transactions, signingTransaction, routingCodes, distributionCodes, signedCodes);
    }

    private List<String> jsonCodes(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        return StreamSupport.stream(value.spliterator(), false)
                .map(JsonNode::asText)
                .filter(code -> code != null && !code.isBlank())
                .toList();
    }

    private void collectAuditOrganizations(
            Collection<AuditLog> audits,
            Set<String> organizationCodes,
            Set<Long> organizationIds) {
        for (AuditLog audit : audits) {
            addIfPresent(organizationCodes, audit.getActorOrganizationCode());
            addIfPresent(organizationIds, audit.getActorOrganizationId());
            addIfPresent(organizationIds, audit.getReceiverOrganizationId());
            addIfPresent(organizationCodes, detailText(audit.getDetail(), "nextReceiver"));
        }
    }

    private void collectFlowOrganizations(
            TimelineSource source,
            Set<String> organizationCodes,
            Set<Long> organizationIds) {
        if (source == null) return;
        addIfPresent(organizationIds, source.document().getSenderOrgId());
        organizationCodes.addAll(source.routingCodes());
        organizationCodes.addAll(source.distributionCodes());
        if (source.signingTransaction() == null) {
            source.transactions().stream()
                    .map(ExchangeTransactions::getReceiverOrgId)
                    .forEach(id -> addIfPresent(organizationIds, id));
        }
    }

    private <T> void addIfPresent(Set<T> values, T value) {
        if (value != null) values.add(value);
    }

    private List<AuditTimelinePageResponse.SigningOrganization> buildSigningOrganizations(
            TimelineSource source,
            Map<String, String> organizationNamesByCode,
            Map<Long, String> organizationNamesById) {
        ExchangeTransactions transaction = source.signingTransaction();
        if (transaction == null) return null;

        List<AuditTimelinePageResponse.SigningOrganization> steps = new ArrayList<>();
        steps.add(new AuditTimelinePageResponse.SigningOrganization(
                1,
                organizationName(source.document().getSenderOrgId(), organizationNamesById),
                AuditTimelinePageResponse.SigningStatus.COMPLETED));

        int currentStep = transaction.getCurrentStep() != null ? transaction.getCurrentStep() : 0;
        SigningFlowStatus flowStatus = transaction.getSigningFlowStatus();
        boolean completed = flowStatus == SigningFlowStatus.COMPLETED
                || flowStatus == SigningFlowStatus.COMPLETED_READY_FOR_DISTRIBUTION;
        boolean rejected = flowStatus == SigningFlowStatus.REJECTED;

        for (int index = 0; index < source.routingCodes().size(); index++) {
            String code = source.routingCodes().get(index);
            AuditTimelinePageResponse.SigningStatus status;
            if (completed || source.signedCodes().contains(code) || index < currentStep) {
                status = AuditTimelinePageResponse.SigningStatus.COMPLETED;
            } else if (rejected && index == currentStep) {
                status = AuditTimelinePageResponse.SigningStatus.REJECTED;
            } else if (index == currentStep) {
                status = AuditTimelinePageResponse.SigningStatus.CURRENT;
            } else {
                status = AuditTimelinePageResponse.SigningStatus.PENDING;
            }
            steps.add(new AuditTimelinePageResponse.SigningOrganization(
                    index + 2,
                    organizationName(code, organizationNamesByCode),
                    status));
        }
        return steps;
    }

    private String currentSigningOrganization(
            TimelineSource source,
            Map<String, String> organizationNamesByCode) {
        ExchangeTransactions transaction = source.signingTransaction();
        if (transaction == null || transaction.getSigningFlowStatus() == SigningFlowStatus.REJECTED
                || transaction.getSigningFlowStatus() == SigningFlowStatus.COMPLETED
                || transaction.getSigningFlowStatus() == SigningFlowStatus.COMPLETED_READY_FOR_DISTRIBUTION) {
            return null;
        }
        int currentStep = transaction.getCurrentStep() != null ? transaction.getCurrentStep() : 0;
        if (currentStep < 0 || currentStep >= source.routingCodes().size()) return null;
        return organizationName(source.routingCodes().get(currentStep), organizationNamesByCode);
    }

    private List<String> receiverOrganizations(
            TimelineSource source,
            Map<String, String> organizationNamesByCode,
            Map<Long, String> organizationNamesById) {
        if (source.signingTransaction() != null) {
            return source.distributionCodes().stream()
                    .map(code -> organizationName(code, organizationNamesByCode))
                    .distinct()
                    .toList();
        }
        return source.transactions().stream()
                .map(ExchangeTransactions::getReceiverOrgId)
                .map(id -> organizationName(id, organizationNamesById))
                .distinct()
                .toList();
    }

    private void validateAccess() {
        // TODO: Bật lại kiểm tra quyền sau khi hoàn tất test API audit log trên Swagger.
    }

    private void validateTimeRange(AuditLogFilterRequest filter) {
        if (filter.getFrom() != null && filter.getTo() != null && filter.getFrom().isAfter(filter.getTo())) {
            throw new InvalidInputException("Thời gian bắt đầu không được sau thời gian kết thúc");
        }
    }

    private String normalizeAction(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        try {
            return AuditAction.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException("Audit action không hợp lệ: " + value);
        }
    }

    private String normalizeResult(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        try {
            return AuditOutcome.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException("Audit result không hợp lệ: " + value);
        }
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed.toUpperCase(Locale.ROOT) : null;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private AuditLogResponse toResponse(AuditLog audit) {
        return AuditLogResponse.builder()
                .eventId(audit.getEventId())
                .category(audit.getCategory())
                .action(audit.getAction())
                .result(audit.getResult())
                .actorOrganizationCode(audit.getActorOrganizationCode())
                .documentCode(audit.getDocumentCode())
                .transactionCode(audit.getTransactionCode())
                .masterTransactionCode(audit.getMasterTransactionCode())
                .requestId(audit.getRequestId())
                .sourceIp(audit.getSourceIp())
                .userAgent(audit.getUserAgent())
                .errorCode(audit.getErrorCode())
                .errorMessage(audit.getErrorMessage())
                .reason(audit.getReason())
                .metadata(audit.getDetail())
                .beforeState(audit.getBeforeState())
                .afterState(audit.getAfterState())
                .visibilityScope(audit.getVisibilityScope())
                .createdAt(audit.getCreatedAt())
                .build();
    }

    private AuditTimelineItemResponse toTimelineItem(
            AuditLog audit,
            Map<Long, String> organizationNamesById,
            Map<String, String> organizationNamesByCode) {
        String displayName = actionDisplayName(audit.getAction());
        String receiverOrganization = receiverOrganization(
                audit, organizationNamesById, organizationNamesByCode);
        return new AuditTimelineItemResponse(
                audit.getCreatedAt(),
                audit.getDocumentCode(),
                audit.getTransactionCode(),
                displayName,
                actorName(audit, organizationNamesById, organizationNamesByCode),
                receiverOrganization,
                audit.getResult(),
                eventDescription(audit, displayName, receiverOrganization),
                eventReason(audit));
    }

    private String actorName(
            AuditLog audit,
            Map<Long, String> organizationNamesById,
            Map<String, String> organizationNamesByCode) {
        if (AuditAction.MULTI_SIGNATURE_VALIDATED.name().equals(audit.getAction())
                || "SYSTEM".equalsIgnoreCase(audit.getActorType())
                || "GATEWAY".equalsIgnoreCase(audit.getActorId())) {
            return GATEWAY_ACTOR_NAME;
        }
        String organizationCode = trimToNull(audit.getActorOrganizationCode());
        if (organizationCode != null) {
            return organizationName(organizationCode, organizationNamesByCode);
        }
        if (audit.getActorOrganizationId() != null) {
            return organizationName(audit.getActorOrganizationId(), organizationNamesById);
        }
        String actorId = trimToNull(audit.getActorId());
        return actorId != null
                ? organizationNamesByCode.getOrDefault(actorId, actorId)
                : UNKNOWN_ACTOR_NAME;
    }

    private String receiverOrganization(
            AuditLog audit,
            Map<Long, String> organizationNamesById,
            Map<String, String> organizationNamesByCode) {
        String nextReceiverCode = detailText(audit.getDetail(), "nextReceiver");
        if (nextReceiverCode != null) {
            return organizationName(nextReceiverCode, organizationNamesByCode);
        }
        return audit.getReceiverOrganizationId() != null
                ? organizationName(audit.getReceiverOrganizationId(), organizationNamesById)
                : null;
    }

    private String eventDescription(
            AuditLog audit,
            String actionName,
            String receiverOrganization) {
        if (AuditAction.MULTI_SIGNATURE_STEP_COMPLETED.name().equals(audit.getAction())) {
            int completedStep = detailInt(audit.getDetail(), "verifiedSignaturesCount",
                    detailInt(audit.getDetail(), "currentStep", 0) + 1);
            return receiverOrganization != null
                    ? "Hoàn tất bước " + completedStep + ". Đã chuyển tiếp tới: " + receiverOrganization
                    : "Hoàn tất bước " + completedStep + ". Luồng ký đã hoàn thành";
        }
        if (AuditAction.MULTI_SIGNATURE_VALIDATED.name().equals(audit.getAction())) {
            int signatureCount = detailInt(audit.getDetail(), "signatureCount", 0);
            int verifiedCount = detailInt(audit.getDetail(), "verifiedCount", 0);
            return "Xác thực " + verifiedCount + "/" + signatureCount + " chữ ký số hợp lệ";
        }
        if (AuditAction.TRANSACTION_CREATED.name().equals(audit.getAction()) && receiverOrganization != null) {
            return "Khởi tạo giao dịch và chuyển tới: " + receiverOrganization;
        }
        return actionName;
    }

    private String eventReason(AuditLog audit) {
        String reason = trimToNull(audit.getReason());
        if (reason != null) return reason;
        return AuditOutcome.SUCCESS.name().equals(audit.getResult())
                ? null
                : trimToNull(audit.getErrorMessage());
    }

    private String detailText(JsonNode detail, String fieldName) {
        if (detail == null || !detail.hasNonNull(fieldName)) return null;
        return trimToNull(detail.path(fieldName).asText());
    }

    private int detailInt(JsonNode detail, String fieldName, int fallback) {
        return detail != null && detail.hasNonNull(fieldName)
                ? detail.path(fieldName).asInt(fallback)
                : fallback;
    }

    private String organizationName(String code, Map<String, String> namesByCode) {
        return namesByCode.getOrDefault(code, code);
    }

    private String organizationName(Long id, Map<Long, String> namesById) {
        return namesById.getOrDefault(id, UNKNOWN_ACTOR_NAME);
    }

    private String actionDisplayName(String action) {
        try {
            return AuditAction.valueOf(action).getDisplayName();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return action != null ? action.replace('_', ' ') : "Hoạt động hệ thống";
        }
    }

    private record TimelineSource(
            Document document,
            List<ExchangeTransactions> transactions,
            ExchangeTransactions signingTransaction,
            List<String> routingCodes,
            List<String> distributionCodes,
            Set<String> signedCodes) {
    }

}

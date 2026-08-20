package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.RevokeDocumentRequest;
import com.TrucVanban.exchange.dto.request.UpdateDocumentRequest;
import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.DocumentDetailResponse;
import com.TrucVanban.exchange.dto.response.ExchangeDocumentResponse;
import com.TrucVanban.exchange.dto.response.ReceiveDocumentResponse;
import com.TrucVanban.exchange.dto.response.RevokeDocumentResponse;
import com.TrucVanban.exchange.dto.response.TransactionReceivedStatusResponse;
import com.TrucVanban.exchange.dto.response.TransactionSendStatusResponse;
import com.TrucVanban.exchange.entity.*;
import com.TrucVanban.exchange.enums.DocumentStatus;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.mapper.DocumentMapper;
import com.TrucVanban.exchange.repository.*;
import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.exchange.service.ExchangeService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ForbiddenException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.shared.outbox.OutboxEventConstants;
import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import com.TrucVanban.shared.utils.NumberUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExchangeServiceImpl implements ExchangeService {

    RegistryService registryService;
    DocumentMapper documentMapper;
    DocumentRepository documentRepository;
    ExchangeTransactionsRepository exchangeTransactionsRepository;
    DocumentVersionRepository documentVersionRepository;
    DocumentReplacementRepository documentReplacementRepository;
    DocumentReceiverRepository documentReceiverRepository;
    StatusHistoryRepository statusHistoryRepository;
    AuditLogService auditLogService;
    OutboxEventRepository outboxEventRepository;
    ObjectMapper objectMapper;
    AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public List<ExchangeDocumentResponse> exchangeDocument(ExchangeDocumentRequest request) {
        log.info("[exchangeDocument] Bắt đầu gửi văn bản: sender={}, receivers={}",
                request.getSenderCode(), request.getReceiverCodes());
        if (documentRepository.existsDocumentByDocumentCode(request.getDocumentCode())) {
            throw new DuplicateResourceException("Mã văn bản đã tồn tại: " + request.getDocumentCode());
        }

        Long senderId = registryService.getOrganizationIdByCode(request.getSenderCode());
        List<Long> receiverIds = registryService.getOrganizationIdsByCode(request.getReceiverCodes());

        // Xử lý thay thế văn bản nếu có replacedDocumentCode
        Document replacedDoc = null;
        if (request.getReplacedDocumentCode() != null && !request.getReplacedDocumentCode().isBlank()) {
            replacedDoc = documentRepository.findByDocumentCode(request.getReplacedDocumentCode())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản cần thay thế: " + request.getReplacedDocumentCode()));
            if (!senderId.equals(replacedDoc.getSenderOrgId())) {
                throw new ForbiddenException("Chỉ cơ quan gửi gốc mới có quyền thay thế văn bản này");
            }
            if (replacedDoc.getStatus() != DocumentStatus.ACTIVE && replacedDoc.getStatus() != DocumentStatus.RECALLED) {
                throw new BusinessLogicException("Không thể thay thế văn bản có trạng thái: " + replacedDoc.getStatus());
            }
        }

        // Lưu Document
        Document document = documentMapper.toDocument(request);
        document.setSenderOrgId(senderId);
        document = documentRepository.saveAndFlush(document);
        log.info("[exchangeDocument] Lưu document thành công: documentId={}, code={}",
                document.getId(), document.getDocumentCode());

        // Lưu DocumentVersion - dùng storagePath và payloadChecksum từ request
        // (Client đã tự upload file lên MinIO và tính SHA-256 trước khi gọi API)
        DocumentVersion existDocumentVersion = documentVersionRepository
                .findTopByDocumentIdOrderByVersionNoDesc(document.getId())
                .orElse(null);
        Integer versionNo = existDocumentVersion != null ? existDocumentVersion.getVersionNo() + 1 : 1;

        DocumentVersion documentVersion = DocumentVersion.builder()
                .documentId(document.getId())
                .versionNo(versionNo)
                .storagePath(request.getStoragePath())
                .checksum(request.getPayloadChecksum())
                .fileSize(null)  // Client không bắt buộc truyền fileSize
                .createdBy(registryService.getOrganizationNameById(senderId))
                .build();

        documentVersionRepository.save(documentVersion);
        log.info("[exchangeDocument] Lưu document version thành công: documentId={}, version={}, path={}",
                document.getId(), versionNo, request.getStoragePath());

        // Lưu quan hệ thay thế nếu có
        if (replacedDoc != null) {
            replacedDoc.setStatus(DocumentStatus.REPLACED);
            documentRepository.save(replacedDoc);

            DocumentReplacement replacement = DocumentReplacement.builder()
                    .replacedDocumentId(replacedDoc.getId())
                    .replacementDocumentId(document.getId())
                    .reason("Thay thế bởi văn bản " + document.getDocumentCode())
                    .build();
            documentReplacementRepository.save(replacement);

            auditLogService.log("DOCUMENT_REPLACED", "ORGANIZATION", request.getSenderCode(), "SUCCESS",
                    String.format("{\"oldDoc\":\"%s\",\"newDoc\":\"%s\"}", replacedDoc.getDocumentCode(), document.getDocumentCode()),
                    null, document.getId());
        }

        // Tạo ExchangeTransactions cho từng receiver
        List<ExchangeDocumentResponse> exchangeDocumentResponses = new ArrayList<>();
        final Long finalDocumentId = document.getId();
        List<ExchangeTransactions> listTransaction = new ArrayList<>();
        for (Long receiverId : receiverIds) {
            String transactionCode = generateTransactionCode("EXCHANGE");
            Integer priority = NumberUtils.isNullOrNegative(request.getPriority()) ? 0 : request.getPriority();

            ExchangeTransactions transaction = ExchangeTransactions.builder()
                    .transactionCode(transactionCode)
                    .documentId(finalDocumentId)
                    .senderOrgId(senderId)
                    .receiverOrgId(receiverId)
                    .priority(priority)
                    // Sau khi qua filter xác minh chữ ký thành công → trạng thái VALIDATED
                    .currentStatus(TransactionStatus.VALIDATED)
                    .signatureStatus(SignatureStatus.VALID)
                    .build();
            listTransaction.add(transaction);

            exchangeDocumentResponses.add(
                    ExchangeDocumentResponse.builder()
                            .transactionCode(transactionCode)
                            .currentStatus(TransactionStatus.VALIDATED)
                            .build()
            );
        }
        listTransaction = exchangeTransactionsRepository.saveAll(listTransaction);
        outboxEventRepository.saveAll(listTransaction.stream()
                .map(this::toRoutingOutboxEvent)
                .toList());
        return exchangeDocumentResponses;
    }

    private OutboxEvent toRoutingOutboxEvent(ExchangeTransactions transaction) {
        RoutingRequest routingRequest = RoutingRequest.builder()
                .transactionCode(transaction.getTransactionCode())
                .build();

        return OutboxEvent.builder()
                .aggregateType(OutboxEventConstants.AGGREGATE_TYPE_EXCHANGE_TRANSACTION)
                .aggregateId(transaction.getId())
                .eventType(OutboxEventConstants.EVENT_TYPE_ROUTING_REQUEST)
                .payload(objectMapper.valueToTree(routingRequest))
                .build();
    }

    @Override
    @Transactional
    public ReceiveDocumentResponse ackDocument(ReceiveDocumentRequest request) {
        Long receiverId = registryService.getOrganizationIdByCode(request.getReceiverCode());
        ExchangeTransactions transaction = exchangeTransactionsRepository
                .findByTransactionCodeAndCurrentStatus(request.getTransactionCode(), TransactionStatus.DELIVERED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy giao dịch đã được luân chuyển có code: " + request.getTransactionCode()));

        if (!receiverId.equals(transaction.getReceiverOrgId())) {
            throw new ForbiddenException("Bạn không có quyền ghi nhận văn bản này");
        }

        DocumentReceiver receiver = documentMapper.toDocumentReceiver(request);
        receiver.setDocumentId(transaction.getDocumentId());
        receiver.setReceiverOrgId(receiverId);
        documentReceiverRepository.save(receiver);

        StatusHistory statusHistory = documentMapper.toStatusHistory(request);
        statusHistory.setTransactionId(transaction.getId());
        statusHistory.setActorOrgId(receiverId);
        statusHistoryRepository.save(statusHistory);

        // Ghi audit log ACK
        auditLogService.log("ACK_RECEIVED", "ORGANIZATION", request.getReceiverCode(), "SUCCESS",
                String.format("{\"transactionCode\":\"%s\",\"businessStatusCode\":\"%s\"}",
                        request.getTransactionCode(), request.getBusinessStatusCode()),
                transaction.getId(), transaction.getDocumentId());

        return ReceiveDocumentResponse.builder()
                .transactionCode(request.getTransactionCode())
                .businessStatusCode(receiver.getBusinessStatusCode())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionSendStatusResponse getTransactionStatus(String senderCode, String transactionCode) {
        Long senderId = registryService.getOrganizationIdByCode(senderCode);
        ExchangeTransactions transaction = exchangeTransactionsRepository.findByTransactionCode(transactionCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giao dịch có code: " + transactionCode));

        if (!senderId.equals(transaction.getSenderOrgId()))
            throw new ForbiddenException("Bạn không có quyền xem giao dịch này");

        return documentMapper.toTransactionStatusResponse(transaction);
    }

    @Override
    public List<TransactionReceivedStatusResponse> getTransactionReceivedStatus(String receiverCode) {
        Long receiverId = registryService.getOrganizationIdByCode(receiverCode);
        List<ExchangeTransactions> transaction = exchangeTransactionsRepository.findByReceiverOrgId(receiverId);

        if (transaction.isEmpty()) return null;

        return transaction.stream().map(t -> {
                    List<StatusHistory> statusHistories = statusHistoryRepository.findByTransactionIdOrderByCreatedAtDesc(t.getId());
                    List<TransactionReceivedStatusResponse.timeline> timelines = new ArrayList<>(statusHistories.stream()
                            .map(sh -> TransactionReceivedStatusResponse.timeline.builder()
                                    .time(sh.getCreatedAt())
                                    .status(sh.getStatusCode().getCode())
                                    .build())
                            .toList());
                    timelines.add(0, TransactionReceivedStatusResponse.timeline.builder()
                            .time(t.getCreatedAt())
                            .status(t.getCurrentStatus().name())
                            .build());
                    return TransactionReceivedStatusResponse.builder()
                            .transactionCode(t.getTransactionCode())
                            .timeline(timelines)
                            .build();
                })
                .toList();
    }

    @Override
    @Transactional
    public RevokeDocumentResponse revokeDocument(String documentCode, RevokeDocumentRequest request) {
        log.info("[revokeDocument] Thu hồi văn bản: documentCode={}, requester={}", documentCode, request.getRequesterCode());
        Document document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản: " + documentCode));
        Long requesterId = registryService.getOrganizationIdByCode(request.getRequesterCode());
        if (!requesterId.equals(document.getSenderOrgId())) {
            throw new ForbiddenException("Chỉ có cơ quan gửi gốc mới được phép thu hồi văn bản này");
        }
        if (document.getStatus() != DocumentStatus.ACTIVE) {
            throw new BusinessLogicException("Không thể thu hồi văn bản có trạng thái: " + document.getStatus().name());
        }

        document.setStatus(DocumentStatus.RECALLED);
        documentRepository.save(document);

        List<ExchangeTransactions> relatedTransactions = exchangeTransactionsRepository.findByDocumentId(document.getId());
        Long receiverId = relatedTransactions.isEmpty() ? requesterId : relatedTransactions.get(0).getReceiverOrgId();

        String transactionCode = generateTransactionCode("REVOKE");

        ExchangeTransactions revokeTxn = ExchangeTransactions.builder()
                .transactionCode(transactionCode)
                .documentId(document.getId())
                .senderOrgId(requesterId)
                .receiverOrgId(receiverId)
                .priority(1)
                .currentStatus(TransactionStatus.RECEIVED)
                .signatureStatus(SignatureStatus.VALID)
                .build();
        exchangeTransactionsRepository.save(revokeTxn);

        auditLogService.log("DOCUMENT_REVOKED", "ORGANIZATION", request.getRequesterCode(), "SUCCESS",
                String.format("{\"documentCode\":\"%s\",\"reason\":\"%s\",\"transactionCode\":\"%s\"}", documentCode, request.getReason(), transactionCode),
                null, document.getId());

        log.info("[revokeDocument] Thu hồi văn bản thành công: documentCode={}, txn={}", documentCode, transactionCode);
        return RevokeDocumentResponse.builder()
                .transactionCode(transactionCode)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocumentDetail(String documentCode) {
        log.info("[getDocumentDetail] Tra cứu chi tiết văn bản: documentCode={}", documentCode);
        Document document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản: " + documentCode));
        List<DocumentVersion> versions = documentVersionRepository.findAllByDocumentIdOrderByVersionNoAsc(document.getId());
        List<DocumentReplacement> replacements = documentReplacementRepository.findAllRelatedByDocumentId(document.getId());
        List<AuditLog> auditLogs = auditLogRepository.findByDocumentIdOrderByCreatedAtDesc(document.getId());
        List<DocumentDetailResponse.VersionResponse> versionResponses = versions.stream()
                .map(v -> DocumentDetailResponse.VersionResponse.builder()
                        .versionNo(v.getVersionNo()).storagePath(v.getStoragePath())
                        .checksum(v.getChecksum()).updateReason(v.getUpdateReason())
                        .createdBy(v.getCreatedBy()).createdAt(v.getCreatedAt()).changedFields(null).build())
                .toList();
        List<DocumentDetailResponse.ReplacementRelationResponse> replacementResponses = replacements.stream().map(r -> {
            String replacementCode = documentRepository.findById(r.getReplacementDocumentId()).map(Document::getDocumentCode).orElse(null);
            String replacedCode = documentRepository.findById(r.getReplacedDocumentId()).map(Document::getDocumentCode).orElse(null);
            return DocumentDetailResponse.ReplacementRelationResponse.builder()
                    .replacementDocumentCode(replacementCode).replacedDocumentCode(replacedCode)
                    .reason(r.getReason()).createdAt(r.getCreatedAt()).build();
        }).toList();
        List<DocumentDetailResponse.AuditResponse> auditResponses = auditLogs.stream()
                .map(a -> DocumentDetailResponse.AuditResponse.builder()
                        .action(a.getAction()).actorType(a.getActorType()).actorId(a.getActorId())
                        .result(a.getResult()).detail(a.getDetail() != null ? a.getDetail().toString() : null)
                        .createdAt(a.getCreatedAt()).build())
                .toList();
        return DocumentDetailResponse.builder()
                .documentCode(document.getDocumentCode()).title(document.getTitle())
                .summary(document.getSummary()).documentType(document.getDocumentType())
                .extractedMetadata(document.getExtractedMetadata()).senderOrgId(document.getSenderOrgId())
                .status(document.getStatus()).currentVersion(document.getCurrentVersion())
                .versions(versionResponses).historyVersions(versionResponses).replacements(replacementResponses).auditLogs(auditResponses).build();
    }

    @Override
    @Transactional
    public void updateDocument(String documentCode, UpdateDocumentRequest request) {
        log.info("[updateDocument] Cập nhật văn bản: documentCode={}, requester={}", documentCode, request.getRequesterCode());
        Document document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản: " + documentCode));
        Long requesterId = registryService.getOrganizationIdByCode(request.getRequesterCode());
        if (!requesterId.equals(document.getSenderOrgId())) {
            throw new ForbiddenException("Chỉ có cơ quan gửi gốc mới được phép chỉnh sửa văn bản này");
        }
        if (document.getStatus() != DocumentStatus.ACTIVE) {
            throw new BusinessLogicException("Không thể chỉnh sửa văn bản có trạng thái: " + document.getStatus().name());
        }

        if (request.getTitle() != null && !request.getTitle().equals(document.getTitle())) {
            document.setTitle(request.getTitle());
        }
        if (request.getSummary() != null && !request.getSummary().equals(document.getSummary())) {
            document.setSummary(request.getSummary());
        }
        if (request.getDocumentType() != null && !request.getDocumentType().equals(document.getDocumentType())) {
            document.setDocumentType(request.getDocumentType());
        }
        if (request.getExtractedMetadata() != null && !request.getExtractedMetadata().equals(document.getExtractedMetadata())) {
            document.setExtractedMetadata(request.getExtractedMetadata());
        }

        // Luôn tạo version mới khi gọi PUT update
        DocumentVersion lastVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(document.getId()).orElse(null);
        int newVersionNo = lastVersion != null ? lastVersion.getVersionNo() + 1 : 1;
        String storagePath = (request.getStoragePath() != null && !request.getStoragePath().isBlank()) ? request.getStoragePath() : (lastVersion != null ? lastVersion.getStoragePath() : "");
        String checksum = (request.getPayloadChecksum() != null && !request.getPayloadChecksum().isBlank()) ? request.getPayloadChecksum() : (lastVersion != null ? lastVersion.getChecksum() : "");
        String reason = (request.getUpdateReason() != null && !request.getUpdateReason().isBlank()) ? request.getUpdateReason() : "Cập nhật thông tin văn bản";
        
        DocumentVersion newVersion = DocumentVersion.builder()
                .documentId(document.getId())
                .versionNo(newVersionNo)
                .storagePath(storagePath)
                .checksum(checksum)
                .updateReason(reason)
                .createdBy(request.getRequesterCode())
                .build();
        documentVersionRepository.save(newVersion);
        document.setCurrentVersion(newVersionNo);
        log.info("[updateDocument] Tạo version mới: versionNo={}, documentCode={}", newVersionNo, documentCode);

        documentRepository.save(document);
        auditLogService.log("DOCUMENT_UPDATED", "ORGANIZATION", request.getRequesterCode(), "SUCCESS",
                String.format("{\"documentCode\":\"%s\",\"updateReason\":\"%s\"}", documentCode, reason),
                null, document.getId());
        log.info("[updateDocument] Cập nhật văn bản thành công: documentCode={}", documentCode);
    }

    private String generateTransactionCode(String prefix) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MMdd"));
        String random = String.format("%05d", ThreadLocalRandom.current().nextInt(10000, 99999));
        return "TXN-" + date + "-" + random;
    }
}
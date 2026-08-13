package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import com.TrucVanban.exchange.dto.response.ExchangeDocumentResponse;
import com.TrucVanban.exchange.dto.response.MultiSignatureResponse;
import com.TrucVanban.exchange.dto.response.ReceiveDocumentResponse;
import com.TrucVanban.exchange.dto.response.TransactionReceivedStatusResponse;
import com.TrucVanban.exchange.dto.response.TransactionSendStatusResponse;
import com.TrucVanban.exchange.entity.*;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.SigningFlowStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.mapper.DocumentMapper;
import com.TrucVanban.exchange.repository.*;
import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.exchange.service.ExchangeService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.shared.exception.*;
import com.TrucVanban.shared.outbox.OutboxEventConstants;
import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import com.TrucVanban.shared.utils.NumberUtils;
import com.TrucVanban.shared.utils.StringUtils;
import com.TrucVanban.shared.validator.MultiSignatureValidator;
import com.TrucVanban.shared.validator.SignatureVerificationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExchangeServiceImpl implements ExchangeService {

    private static final String EXCHANGE_DOCUMENT_IDEMPOTENCY_KEY_PREFIX = "idempotency:exchange-document:";
    private static final String IDEMPOTENCY_COMPLETED_VALUE = "COMPLETED";

    RegistryService registryService;

    DocumentMapper documentMapper;

    DocumentRepository documentRepository;
    ExchangeTransactionsRepository exchangeTransactionsRepository;
    DocumentVersionRepository documentVersionRepository;
    DocumentReplacementRepository documentReplacementRepository;
    DocumentReceiverRepository documentReceiverRepository;
    StatusHistoryRepository statusHistoryRepository;
    DocumentSignatureRepository documentSignatureRepository;
    AuditLogService auditLogService;
    OutboxEventRepository outboxEventRepository;
    MultiSignatureValidator multiSignatureValidator;
    ObjectMapper objectMapper;
    StringRedisTemplate redisTemplate;
    private final Object lock  = new Object();

    @Override
    @Transactional
    public List<ExchangeDocumentResponse> exchangeDocument(ExchangeDocumentRequest request, String idempotencyKey) {
        log.info("[exchangeDocument] Bắt đầu gửi văn bản: sender={}, receivers={}",
                request.getSenderCode(), request.getReceiverCodes());
        if (StringUtils.isNullOrBlank(idempotencyKey)) {
            throw new InvalidInputException("Idempotency-Key là bắt buộc");
        }
        String redisIdempotencyKey = buildExchangeDocumentIdempotencyKey(idempotencyKey);
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                redisIdempotencyKey,
                "PROCESSING",
                Duration.ofMinutes(10)
        );
        if(!claimed){
            if(redisTemplate.opsForValue().get(redisIdempotencyKey).equals(IDEMPOTENCY_COMPLETED_VALUE)){
                throw new BusinessLogicException("Yêu cầu đã được xử lý trước đó. Vui lòng không gửi lại.");
            }
            else throw new DuplicateResourceException("Yêu cầu đang được xử lý. Vui lòng thử lại sau.");
        }
        try{
            if (documentRepository.existsDocumentByDocumentCode(request.getDocumentCode())) {
                throw new DuplicateResourceException("Mã văn bản đã tồn tại: " + request.getDocumentCode());
            }

            Long senderId = registryService.getOrganizationIdByCode(request.getSenderCode());
            List<Long> receiverIds = registryService.getOrganizationIdsByCode(request.getReceiverCodes());

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



            // Tạo ExchangeTransactions cho từng receiver
            List<ExchangeDocumentResponse> exchangeDocumentResponses = new ArrayList<>();
            final Long finalDocumentId = document.getId();
            List<ExchangeTransactions> listTransaction = new ArrayList<>();
            for (Long receiverId : receiverIds) {
                String transactionCode = "TXN-" + Year.now().getValue() + "-" + finalDocumentId + "-" + receiverId;
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
            updateIdempotencyAfterCommit(redisIdempotencyKey);
            return exchangeDocumentResponses;
        } catch (Exception e) {
            redisTemplate.delete(redisIdempotencyKey);
            throw e;
        }
    }
    private void updateIdempotencyAfterCommit(String redisKey) {

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisTemplate.opsForValue().set(
                                redisKey,
                                IDEMPOTENCY_COMPLETED_VALUE,
                                Duration.ofMinutes(10)
                        );
                    }

                }
        );

    }

    private String buildExchangeDocumentIdempotencyKey(String idempotencyKey) {
        return EXCHANGE_DOCUMENT_IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
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
    public MultiSignatureResponse processMultiSignatureDocument(MultiSignatureRequest request, String idempotencyKey) {
        log.info("[MultiSig] Bắt đầu xử lý: masterTxCode={}, sender={}, sigs={}",
                request.getMasterTransactionCode(), request.getCurrentSenderCode(),
                request.getSignatures().size());

        // Idempotency check
        if (StringUtils.isNullOrBlank(idempotencyKey)) {
            throw new InvalidInputException("Idempotency-Key là bắt buộc");
        }
        String redisKey = "idempotency:multi-sig:" + idempotencyKey;
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", Duration.ofMinutes(10));
        if (!claimed) {
            String current = redisTemplate.opsForValue().get(redisKey);
            if (IDEMPOTENCY_COMPLETED_VALUE.equals(current)) {
                throw new BusinessLogicException("Yêu cầu đã được xử lý trước đó. Vui lòng không gửi lại.");
            }
            throw new DuplicateResourceException("Yêu cầu đang được xử lý. Vui lòng thử lại sau.");
        }

        try {
            // ---- Tầng 2: Document Layer — Xác minh PDF đa chữ ký ----
            List<SignatureVerificationResult> verificationResults;
            try {
                verificationResults = multiSignatureValidator.verifyAll(request.getStoragePath(), request.getSignatures());
            } catch (java.io.IOException e) {
                log.error("[MultiSig] Không thể tải file PDF từ MinIO: storagePath={}, error={}",
                        request.getStoragePath(), e.getMessage(), e);
                throw new BusinessLogicException("Không thể tải file PDF để xác minh: " + e.getMessage());
            }

            // Kiểm tra có chữ ký nào thất bại không
            SignatureVerificationResult firstFailure = verificationResults.stream()
                    .filter(r -> !r.isValid())
                    .findFirst()
                    .orElse(null);

            if (firstFailure != null) {
                log.warn("[MultiSig] Xác minh PDF thất bại tại chữ ký #{}: {}",
                        firstFailure.getSignatureOrder(), firstFailure.getFailureReason());
                throw new BusinessLogicException(String.format(
                        "Xác minh chữ ký thất bại tại bước #%d (%s): %s",
                        firstFailure.getSignatureOrder(),
                        firstFailure.getSignerCode(),
                        firstFailure.getFailureReason()));
            }

            log.info("[MultiSig] Tầng 2 PASS — {} chữ ký hợp lệ", verificationResults.size());

            // ---- State Machine — Tìm hoặc tạo ExchangeTransactions ----
            SignatureRequest lastSig = getLastSignature(request.getSignatures());
            String signerRole = lastSig.getSignerRole();

            MultiSignatureResponse result;

            if ("INITIATOR".equalsIgnoreCase(signerRole)) {
                result = handleInitiator(request, verificationResults);
            } else if ("FINAL_APPROVER".equalsIgnoreCase(signerRole)) {
                result = handleFinalApprover(request, verificationResults);
            } else {
                // REVIEWER (mặc định)
                result = handleReviewer(request, verificationResults);
            }

            updateIdempotencyAfterCommit(redisKey);
            return result;

        } catch (Exception e) {
            redisTemplate.delete(redisKey);
            throw e;
        }
    }


    private MultiSignatureResponse handleInitiator(MultiSignatureRequest request,
                                                    List<SignatureVerificationResult> results) {
        log.info("[MultiSig-INITIATOR] Tạo mới giao dịch: masterTxCode={}", request.getMasterTransactionCode());

        // Kiểm tra không bị duplicate masterTransactionCode
        if (exchangeTransactionsRepository.findByMasterTransactionCode(request.getMasterTransactionCode()).isPresent()) {
            throw new DuplicateResourceException("masterTransactionCode đã tồn tại: " + request.getMasterTransactionCode());
        }

        // Nếu không có routingList thì không thể khởi tạo
        if (request.getRoutingList() == null || request.getRoutingList().isEmpty()) {
            throw new InvalidInputException("routingList là bắt buộc khi INITIATOR khởi tạo giao dịch");
        }

        Long senderId = registryService.getOrganizationIdByCode(request.getCurrentSenderCode());
        JsonNode routingListJson = objectMapper.valueToTree(request.getRoutingList());
        JsonNode distributionListJson = objectMapper.valueToTree(
                request.getDistributionList() != null ? request.getDistributionList() : List.of());

        String transactionCode = generateTransactionCode(request.getMasterTransactionCode());

        // Luồng ký nối không đi qua POST /exchange nên Document phải được tạo ở đây.
        if (documentRepository.existsDocumentByDocumentCode(request.getDocumentCode())) {
            throw new DuplicateResourceException("documentCode đã tồn tại: " + request.getDocumentCode());
        }

        Document document = Document.builder()
                .documentCode(request.getDocumentCode())
                .senderOrgId(senderId)
                .build();
        document = documentRepository.saveAndFlush(document);
        log.info("[MultiSig-INITIATOR] Đã tạo Document: id={}, code={}", document.getId(), document.getDocumentCode());

        // ── Bước 2: Tạo DocumentVersion ─────────────────────────────────────────
        DocumentVersion documentVersion = DocumentVersion.builder()
                .documentId(document.getId())
                .versionNo(1)
                .storagePath(request.getStoragePath())
                .checksum(null)
                .createdBy(registryService.getOrganizationNameById(senderId))
                .build();
        documentVersionRepository.save(documentVersion);
        log.info("[MultiSig-INITIATOR] Đã tạo DocumentVersion: documentId={}, path={}",
                document.getId(), request.getStoragePath());

        ExchangeTransactions transaction = ExchangeTransactions.builder()
                .masterTransactionCode(request.getMasterTransactionCode())
                .transactionCode(transactionCode)
                .documentId(document.getId())           
                .senderOrgId(senderId)
                .receiverOrgId(senderId) // INITIATOR chưa có receiverOrgId cụ thể, tạm đặt bằng senderId
                .routingList(routingListJson)
                .distributionList(distributionListJson)
                .currentStep(0)
                .currentStoragePath(request.getStoragePath())
                .currentStatus(TransactionStatus.VALIDATED)
                .signingFlowStatus(SigningFlowStatus.INITIATED)
                .priority(1)
                .signatureStatus(SignatureStatus.VALID)
                .build();
        transaction = exchangeTransactionsRepository.save(transaction);

        // Lưu document signatures
        saveDocumentSignatures(transaction.getId(), results, request);

        // Push Outbox sang cơ quan B (routingList[0])
        String nextReceiver = request.getRoutingList().get(0);
        outboxEventRepository.save(toMultiSigOutboxEvent(transaction, nextReceiver,
                OutboxEventConstants.EVENT_TYPE_ROUTING_REQUEST));

        log.info("[MultiSig-INITIATOR] Tạo thành công. txId={}, documentId={}, nextReceiver={}",
                transaction.getId(), document.getId(), nextReceiver);

        return MultiSignatureResponse.builder()
                .transactionId(transaction.getId())
                .masterTransactionCode(request.getMasterTransactionCode())
                .signingFlowStatus(SigningFlowStatus.INITIATED.name())
                .currentStep(0)
                .nextReceiver(nextReceiver)
                .verifiedSignaturesCount(results.size())
                .build();
    }

    /**
     * Nhánh REVIEWER: Tăng currentStep, push Outbox sang cơ quan tiếp theo.
     */
    private MultiSignatureResponse handleReviewer(MultiSignatureRequest request,
                                                   List<SignatureVerificationResult> results) {
        ExchangeTransactions transaction = findTransactionByMasterCode(request.getMasterTransactionCode());

        int newStep = transaction.getCurrentStep() + 1;
        transaction.setCurrentStep(newStep);
        transaction.setCurrentStoragePath(request.getStoragePath());
        transaction.setSigningFlowStatus(SigningFlowStatus.WAITING_FOR_ROUTING_SIGN);
        exchangeTransactionsRepository.save(transaction);

        // Lưu document signatures (chưa có bước này, chỉ lưu chữ ký mới nhất)
        saveDocumentSignatures(transaction.getId(), results, request);

        // Xác định nextReceiver từ routingList
        List<String> routingList = objectMapper.convertValue(
                transaction.getRoutingList(), List.class);
        String nextReceiver = newStep < routingList.size() ? routingList.get(newStep) : null;

        if (nextReceiver != null) {
            outboxEventRepository.save(toMultiSigOutboxEvent(transaction, nextReceiver,
                    OutboxEventConstants.EVENT_TYPE_ROUTING_REQUEST));
            log.info("[MultiSig-REVIEWER] Đã push Outbox sang: {}, step={}", nextReceiver, newStep);
        }

        return MultiSignatureResponse.builder()
                .transactionId(transaction.getId())
                .masterTransactionCode(request.getMasterTransactionCode())
                .signingFlowStatus(SigningFlowStatus.WAITING_FOR_ROUTING_SIGN.name())
                .currentStep(newStep)
                .nextReceiver(nextReceiver)
                .verifiedSignaturesCount(results.size())
                .build();
    }

    /**
     * Nhánh FINAL_APPROVER: Đánh dấu COMPLETED_READY_FOR_DISTRIBUTION,
     * insert OutboxEvent cho tất cả cơ quan trong distributionList (song song).
     */
    private MultiSignatureResponse handleFinalApprover(MultiSignatureRequest request,
                                                        List<SignatureVerificationResult> results) {
        ExchangeTransactions transaction = findTransactionByMasterCode(request.getMasterTransactionCode());

        transaction.setCurrentStoragePath(request.getStoragePath());
        transaction.setSigningFlowStatus(SigningFlowStatus.COMPLETED_READY_FOR_DISTRIBUTION);
        exchangeTransactionsRepository.save(transaction);

        saveDocumentSignatures(transaction.getId(), results, request);

        // Phân phối song song cho tất cả org trong distributionList
        List<String> distributionList = objectMapper.convertValue(
                transaction.getDistributionList(), List.class);

        if (distributionList != null && !distributionList.isEmpty()) {
            List<OutboxEvent> distributionEvents = distributionList.stream()
                    .map(receiverCode -> toMultiSigOutboxEvent(transaction, receiverCode,
                            OutboxEventConstants.EVENT_TYPE_DISTRIBUTION_REQUEST))
                    .toList();
            outboxEventRepository.saveAll(distributionEvents);
            log.info("[MultiSig-FINAL_APPROVER] Đã push {} OutboxEvent phân phối", distributionEvents.size());
        }

        return MultiSignatureResponse.builder()
                .transactionId(transaction.getId())
                .masterTransactionCode(request.getMasterTransactionCode())
                .signingFlowStatus(SigningFlowStatus.COMPLETED_READY_FOR_DISTRIBUTION.name())
                .currentStep(transaction.getCurrentStep())
                .nextReceiver(null)
                .verifiedSignaturesCount(results.size())
                .build();
    }

    
    private void saveDocumentSignatures(Long transactionId,
                                         List<SignatureVerificationResult> results,
                                         MultiSignatureRequest request) {
        long existingCount = documentSignatureRepository.countByTransactionId(transactionId);
        long expectedNewStart = existingCount + 1;

        List<DocumentSignature> toSave = new ArrayList<>();
        for (SignatureVerificationResult result : results) {
            if (result.getSignatureOrder() < expectedNewStart) continue; // Đã lưu rồi

            SignatureRequest payloadSig = request.getSignatures().stream()
                    .filter(s -> s.getSignatureOrder() != null
                            && s.getSignatureOrder() == result.getSignatureOrder())
                    .findFirst()
                    .orElse(null);

            if (payloadSig == null) continue;

            toSave.add(DocumentSignature.builder()
                    .transactionId(transactionId)
                    .signatureOrder(result.getSignatureOrder())
                    .signerCode(result.getSignerCode())
                    .signerRole(payloadSig.getSignerRole())
                    .signatureType(payloadSig.getSignatureType())
                    .certificateSerial(payloadSig.getCertificateSerialNumber())
                    .signatureValue(payloadSig.getSignatureValue())
                    .byteRange(result.getByteRange())
                    .fileUrlAtSigning(request.getStoragePath())
                    .signedAt(parseTimestamp(payloadSig.getTimestamp()))
                    .verifiedAt(LocalDateTime.now())
                    .build());
        }
        if (!toSave.isEmpty()) documentSignatureRepository.saveAll(toSave);
    }

   
    private OutboxEvent toMultiSigOutboxEvent(ExchangeTransactions transaction,
                                               String receiverCode, String eventType) {
        var payload = objectMapper.createObjectNode()
                .put("masterTransactionCode", transaction.getMasterTransactionCode())
                .put("transactionCode", transaction.getTransactionCode())
                .put("receiverCode", receiverCode)
                .put("currentStoragePath", transaction.getCurrentStoragePath());

        return OutboxEvent.builder()
                .aggregateType(OutboxEventConstants.AGGREGATE_TYPE_EXCHANGE_TRANSACTION)
                .aggregateId(transaction.getId())
                .eventType(eventType)
                .payload(payload)
                .build();
    }

    private ExchangeTransactions findTransactionByMasterCode(String masterTransactionCode) {
        return exchangeTransactionsRepository.findByMasterTransactionCode(masterTransactionCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy giao dịch với masterTransactionCode: " + masterTransactionCode));
    }

    private SignatureRequest getLastSignature(List<SignatureRequest> signatures) {
        return signatures.stream()
                .max((a, b) -> Integer.compare(
                        a.getSignatureOrder() != null ? a.getSignatureOrder() : 0,
                        b.getSignatureOrder() != null ? b.getSignatureOrder() : 0))
                .orElseThrow(() -> new InvalidInputException("Mảng signatures[] rỗng"));
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return OffsetDateTime.parse(timestamp).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String generateTransactionCode(String masterTransactionCode) {
        return "TXN-" + Year.now().getValue() + "-" + System.currentTimeMillis() % 100000;
    }
}

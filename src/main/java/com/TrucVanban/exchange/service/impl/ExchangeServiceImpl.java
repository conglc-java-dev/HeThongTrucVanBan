package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.ExchangeDocumentResponse;
import com.TrucVanban.exchange.dto.response.ReceiveDocumentResponse;
import com.TrucVanban.exchange.dto.response.TransactionReceivedStatusResponse;
import com.TrucVanban.exchange.dto.response.TransactionSendStatusResponse;
import com.TrucVanban.exchange.entity.*;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.mapper.DocumentMapper;
import com.TrucVanban.exchange.repository.*;
import com.TrucVanban.exchange.service.ExchangeService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.shared.config.RabbitMQConfig;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ForbiddenException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.shared.utils.NumberUtils;
import com.TrucVanban.storage.service.MinioService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExchangeServiceImpl implements ExchangeService {

    RegistryService registryService;
    MinioService minioService;

    DocumentMapper documentMapper;

    DocumentRepository documentRepository;
    ExchangeTransactionsRepository exchangeTransactionsRepository;
    DocumentVersionRepository documentVersionRepository;
    DocumentReplacementRepository documentReplacementRepository;
    RabbitTemplate rabbitTemplate;
//    DocumentReplacementRepository documentReplacementRepository;
    DocumentReceiverRepository documentReceiverRepository;
    StatusHistoryRepository statusHistoryRepository;

    @Override
    @Transactional
    public List<ExchangeDocumentResponse> exchangeDocument(ExchangeDocumentRequest request) {
        log.info("[exchangeDocument] Bắt đầu gửi văn bản: sender={}, receivers={}", request.getSenderCode(), request.getReceiverCodes());

        Long senderId = registryService.getOrganizationIdByCode(request.getSenderCode());
        List<Long> receiverIds = request.getReceiverCodes().stream()
                .map(registryService::getOrganizationIdByCode)
                .toList();

//        if (!registryService.checkCertificate(request.getSignature(), senderId))
//            throw new ForbiddenException("Certificate is not valid");

        if (documentRepository.existsDocumentByDocumentCode(request.getDocumentCode())) {
            throw new DuplicateResourceException("Mã văn bản đã tồn tại: " + request.getDocumentCode());
        }

        Document document = documentMapper.toDocument(request);
        document.setSenderOrgId(senderId);
        document = documentRepository.saveAndFlush(document);
        log.info("[exchangeDocument] Lưu document thành công: documentId={}, code={}", document.getId(), document.getDocumentCode());

        List<ExchangeDocumentResponse> exchangeDocumentResponses = new ArrayList<>();
        for (Long receiverId : receiverIds) {
            String transactionCode = "TXN-" + Year.now().getValue() + "-" + document.getId() + "-" + receiverId;
            Integer priority = NumberUtils.isNullOrNegative(request.getPriority()) ? 0 : request.getPriority();

            ExchangeTransactions transaction = ExchangeTransactions.builder()
                    .transactionCode(transactionCode)
                    .documentId(document.getId())
                    .senderOrgId(senderId)
                    .receiverOrgId(receiverId)
                    .priority(priority)
                    .currentStatus(TransactionStatus.RECEIVED)
                    .signatureStatus(SignatureStatus.PENDING)
//                    .slaDeadline(LocalDateTime.now().plusHours(registryService.getMaxReceiveHoursByPriority(priority)))
                    .build();

            transaction = exchangeTransactionsRepository.save(transaction);
            log.info("[exchangeDocument] Tạo transaction: code={}, receiverId={}, priority={}", transactionCode, receiverId, priority);
            publishRoutingMessageAfterCommit(transaction);

            exchangeDocumentResponses.add(
                    ExchangeDocumentResponse.builder()
                            .transactionCode(transactionCode)
                            .currentStatus(TransactionStatus.RECEIVED)
                            .build()
            );
        }

        String url = null;
        try {
            url = minioService.upload(request.getPayLoad());

            DocumentVersion existDocumentVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(document.getId())
                    .orElse(null);
            Integer versionNo = existDocumentVersion != null ? existDocumentVersion.getVersionNo() + 1 : 1;

            DocumentVersion documentVersion = DocumentVersion.builder()
                    .documentId(document.getId())
                    .versionNo(versionNo)
                    .storagePath(url)
                    .checksum(calculateFileSHA256(request.getPayLoad()))
                    .fileSize(request.getPayLoad().getSize())
                    .createdBy(registryService.getOrganizationNameById(senderId))
                    .build();

            documentVersionRepository.save(documentVersion);
            log.info("[exchangeDocument] Lưu document version thành công: documentId={}, version={}", document.getId(), versionNo);

        } catch (Throwable e) {
            log.error("[exchangeDocument] Lỗi lưu document version: documentId={}, error={}", document.getId(), e.getMessage(), e);
            if (url != null) minioService.deleteByUrl(url);
            throw new RuntimeException("Không thể lưu file lên MinIO: " + e.getMessage(), e);
        }

        log.info("[exchangeDocument] Hoàn thành gửi văn bản: documentId={}, số nơi nhận={}", document.getId(), receiverIds.size());
        return exchangeDocumentResponses;
    }

    private void publishRoutingMessageAfterCommit(ExchangeTransactions transaction) {
        RoutingRequest routingRequest = RoutingRequest.builder()
                .transactionCode(transaction.getTransactionCode())
                .build();

        Runnable publishTask = () -> {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.DOCUMENT_EXCHANGE,
                    RabbitMQConfig.DOCUMENT_EXCHANGE_ROUTING_KEY,
                    routingRequest
            );
            log.info("[exchangeDocument] Đã publish routing message: transactionCode={}", transaction.getTransactionCode());
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishTask.run();
                }
            });
            return;
        }

        publishTask.run();
    }

    private String calculateFileSHA256(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Không thể tính SHA-256: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ReceiveDocumentResponse ackDocument(ReceiveDocumentRequest request) {
        Long receiverId = registryService.getOrganizationIdByCode(request.getReceiverCode());
        ExchangeTransactions transaction = exchangeTransactionsRepository.findByTransactionCodeAndCurrentStatus(request.getTransactionCode(), TransactionStatus.DELIVERED)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giao dịch đã được luân chuyển có code: " + request.getTransactionCode()));
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

        return ReceiveDocumentResponse.builder()
                .transactionCode(request.getTransactionCode())
                .businessStatusCode(receiver.getBusinessStatusCode())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    //chưa có bảng lưu lịch sử transaction status
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
                    timelines.addFirst(TransactionReceivedStatusResponse.timeline.builder()
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

}

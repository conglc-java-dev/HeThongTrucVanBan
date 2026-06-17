package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.command.DocumentCreateCommand;
import com.TrucVanban.exchange.dto.command.DocumentVersionCreateCommand;
import com.TrucVanban.exchange.dto.command.ExchangeTransactionCreateCommand;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.SenderDocumentResponse;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.DocumentVersion;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.mapper.DocumentMapper;
import com.TrucVanban.exchange.repository.DocumentReplacementRepository;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.DocumentVersionRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.exchange.service.ExchangeService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.utils.NumberUtils;
import com.TrucVanban.storage.service.MinioService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    @Override
    @Transactional
    public List<SenderDocumentResponse> exchangeDocument(ExchangeDocumentRequest request) {
        log.info("[exchangeDocument] Bắt đầu gửi văn bản: sender={}, receivers={}", request.getSenderCode(), request.getReceiverCodes());

        Long senderId = registryService.getOrganizationIdByCode(request.getSenderCode());
        List<Long> receiverIds = request.getReceiverCodes().stream()
                .map(registryService::getOrganizationIdByCode)
                .toList();

//        if (!registryService.checkCertificate(request.getSignature(), senderId))
//            throw new ForbiddenException("Certificate is not valid");

        if(documentRepository.existsDocumentByDocumentCode(request.getDocumentCode())){
            throw new RuntimeException("Mã văn bản đã tồn tại: " + request.getDocumentCode());
        }
        DocumentCreateCommand documentCreateCommand = documentMapper.toDocumentCreateCommand(request);
        documentCreateCommand.setSenderOrgId(senderId);
        Document document = Document.of(documentCreateCommand);
        document = documentRepository.saveAndFlush(document);
        log.info("[exchangeDocument] Lưu document thành công: documentId={}, code={}", document.getId(), document.getDocumentCode());

        List<SenderDocumentResponse> senderDocumentResponses = new ArrayList<>();
        for (Long receiverId : receiverIds) {
            String transactionCode = "TXN-" + Year.now().getValue() + "-" + document.getId() + "-" + receiverId;
            Integer priority = NumberUtils.isNullOrNegative(request.getPriority()) ? 0 : request.getPriority();

            ExchangeTransactionCreateCommand command = ExchangeTransactionCreateCommand.builder()
                    .transactionCode(transactionCode)
                    .documentId(document.getId())
                    .senderOrgId(senderId)
                    .receiverOrgId(receiverId)
                    .priority(priority)
                    .currentStatus(TransactionStatus.RECEIVED)
                    .signatureStatus(SignatureStatus.PENDING)
                    .slaDeadline(LocalDateTime.now().plusHours(registryService.getMaxReceiveHoursByPriority(priority)))
                    .build();

            exchangeTransactionsRepository.save(ExchangeTransactions.of(command));
            log.info("[exchangeDocument] Tạo transaction: code={}, receiverId={}, priority={}", transactionCode, receiverId, priority);

            senderDocumentResponses.add(
                    SenderDocumentResponse.builder()
                            .transactionCode(transactionCode)
                            .currentStatus(TransactionStatus.RECEIVED)
                            .build()
            );
        }

        String url = null;
        try {
            url = minioService.upload(request.getPayLoad());

            DocumentVersion existDocumentVersion = documentVersionRepository.findMaxVersionNoByDocumentId(document.getId())
                    .orElse(null);
            Integer versionNo = existDocumentVersion != null ? existDocumentVersion.getVersionNo() + 1 : 1;

            DocumentVersionCreateCommand documentVersionCreateCommand = DocumentVersionCreateCommand.builder()
                    .documentId(document.getId())
                    .versionNo(versionNo)
                    .storagePath(url)
                    .checksum(calculateFileSHA256(request.getPayLoad()))
                    .fileSize(request.getPayLoad().getSize())
                    .createdBy(registryService.getOrganizationNameById(senderId))
                    .build();

            DocumentVersion documentVersion = DocumentVersion.of(documentVersionCreateCommand);
            documentVersionRepository.save(documentVersion);
            log.info("[exchangeDocument] Lưu document version thành công: documentId={}, version={}", document.getId(), versionNo);

        } catch (Throwable e) {
            log.error("[exchangeDocument] Lỗi lưu document version: documentId={}, error={}", document.getId(), e.getMessage(), e);
            minioService.deleteByUrl(url);
        }

        log.info("[exchangeDocument] Hoàn thành gửi văn bản: documentId={}, số nơi nhận={}", document.getId(), receiverIds.size());
        return senderDocumentResponses;
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
}

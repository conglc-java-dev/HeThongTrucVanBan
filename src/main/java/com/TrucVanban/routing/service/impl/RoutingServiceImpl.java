package com.TrucVanban.routing.service.impl;

import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.routing.dto.response.RoutingResponse;
import com.TrucVanban.routing.service.RoutingService;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.storage.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingServiceImpl implements RoutingService {

    private static final String ROUTING_IDEMPOTENCY_KEY_PREFIX = "idempotency:routing:";

    private final ExchangeTransactionsRepository exchangeTransactionsRepository;
    private final MinioService minioService;
    private final RestClient restClient;
    private final StringRedisTemplate redisTemplate;

    @Override
    public RoutingResponse dispatch(RoutingRequest request) {
        String transactionCode = request.getTransactionCode();
        if (transactionCode == null || transactionCode.isBlank()) {
            throw new BusinessLogicException("Thiếu transactionCode");
        }

        log.info("[routing] Bắt đầu điều hướng: transactionCode={}", transactionCode);


        // Idempotency Check
        String redisKey = ROUTING_IDEMPOTENCY_KEY_PREFIX + transactionCode + ":" + request.getReceiverCode();
        Boolean isFirstClaim = redisTemplate.opsForValue().setIfAbsent(
                redisKey,
                "PROCESSING",
                Duration.ofMinutes(10));
        if (!isFirstClaim) {
            // throw new BusinessLogicException("Giao dịch đã được xử lý trước đó: " + transactionCode);
            // day la luong ngam cua rabbit mq , kh co http request nen khi nem ra exception -> spring ampq se hieu la consumer that bai -> se gui lai -> gay treo
            return buildResponse(transactionCode);
        }

        try {

            byte[] fileContent = downloadFile(request.getStoragePath());
            String fileName = buildFileName(request.getDocumentCode(), request.getVersionNo(), request.getStoragePath());

            sendToReceiver(request, fileContent, fileName);

            redisTemplate.opsForValue().set(redisKey, "COMPLETED", Duration.ofMinutes(10));

            // Mở Transaction ngắn chỉ để update trạng thái DB sau khi HTTP thành công
            markDispatched(transactionCode);

            log.info("[routing] Điều hướng thành công: transactionCode={}", transactionCode);
            return buildResponse(transactionCode);
        } catch (Exception e) {
                redisTemplate.delete(redisKey);
            throw e;
        }
    }

    private byte[] downloadFile(String storagePath) {
        try {
            return minioService.download(storagePath);
        } catch (Exception e) {
            log.error("[routing] Lỗi tải file: path={}, error={}", storagePath, e.getMessage());
            throw new BusinessLogicException("Không thể tải tệp từ bộ lưu trữ");
        }
    }

    private void sendToReceiver(RoutingRequest request, byte[] fileContent, String fileName) {
        log.info("[routing] Gửi tới endpoint: {}", request.getReceiveEndpoint());

        MultiValueMap<String, Object> body = buildMultipartBody(request, fileContent, fileName);

        ResponseEntity<String> response = restClient.post()
                .uri(request.getReceiveEndpoint())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("[routing] Gửi thất bại: transactionCode={}, status={}", request.getTransactionCode(), response.getStatusCode());
            throw new BusinessLogicException("Gửi văn bản tới đơn vị nhận không thành công: " + response.getStatusCode());
        }
    }

    @Transactional
    public void markDispatched(String transactionCode) {
        Optional<ExchangeTransactions> transaction = exchangeTransactionsRepository.findByTransactionCode(transactionCode);
        transaction.ifPresent(t -> {
            t.setCurrentStatus(TransactionStatus.DISPATCHED);
            exchangeTransactionsRepository.save(t);
        });
    }

    private MultiValueMap<String, Object> buildMultipartBody(RoutingRequest request, byte[] fileContent, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("transactionCode", request.getTransactionCode());
        body.add("priority", String.valueOf(request.getPriority()));
        body.add("versionNo", String.valueOf(request.getVersionNo()));
        body.add("senderCode", request.getSenderCode());
        body.add("receiverCode", request.getReceiverCode());
        addIfNotNull(body, "documentCode", request.getDocumentCode());
        addIfNotNull(body, "title", request.getTitle());
        addIfNotNull(body, "summary", request.getSummary());
        addIfNotNull(body, "documentType", request.getDocumentType());
        addIfNotNull(body, "senderName", request.getSenderName());
        addIfNotNull(body, "receiverName", request.getReceiverName());
        if (request.getExtractedMetadata() != null) {
            body.add("extractedMetadata", request.getExtractedMetadata().toString());
        }
        body.add("file", new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() { return fileName; }

            @Override
            public long contentLength() { return fileContent.length; }
        });
        return body;
    }

    private void addIfNotNull(MultiValueMap<String, Object> body, String key, Object value) {
        if (value != null) {
            body.add(key, value);
        }
    }

    private String buildFileName(String documentCode, Integer versionNo, String storagePath) {
        String extension = storagePath != null && storagePath.contains(".")
                ? storagePath.substring(storagePath.lastIndexOf('.'))
                : "";
        return String.format("%s-v%d%s", documentCode, versionNo, extension);
    }

    private RoutingResponse buildResponse(String transactionCode) {
        return RoutingResponse.builder()
                .transactionCode(transactionCode)
                .currentStatus(TransactionStatus.DISPATCHED)
                .dispatchedAt(LocalDateTime.now())
                .build();
    }
}


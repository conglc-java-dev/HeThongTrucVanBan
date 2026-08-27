package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignAndBuildRequest;
import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SimulateMultiSigRequest;
import com.TrucVanban.exchange.dto.request.send.VisualSignatureRequest;
import com.TrucVanban.exchange.dto.response.FileUploadResponse;
import com.TrucVanban.exchange.service.ClientSimulatorService;
import com.TrucVanban.exchange.service.VisualSignatureService;
import com.TrucVanban.shared.service.MinioService;
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ClientSimulatorServiceImpl implements ClientSimulatorService {

    final RestClient restClient;
    final CanonicalStringBuilder canonicalStringBuilder;
    final MinioService minioService;
    final VisualSignatureService visualSignatureService;

    /** URL Gateway cho flow 1 chữ ký (legacy). Đọc từ application.yml. */
    @Value("${app.client-simulator.gateway-url:http://localhost:8080/api/v1/exchange}")
    String gatewayUrl;

    /** URL Gateway cho flow đa chữ ký nối tiếp. Đọc từ application.yml. */
    @Value("${app.client-simulator.multi-sig-gateway-url:http://localhost:8080/api/v1/exchange-documents/signatures}")
    String multiSigGatewayUrl;

    private static final String KEYS_CLASSPATH_DIR = "keys/";

    // =============================================
    // UPLOAD
    // =============================================

    @Override
    public List<FileUploadResponse> uploadFiles(MultipartFile[] files) throws Exception {
        if (files == null || files.length == 0)
            return new ArrayList<>();

        List<FileUploadResponse> results = new ArrayList<>(files.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (MultipartFile file : files) {
            String objectKey = minioService.upload(file);
            byte[] hashBytes = digest.digest(file.getBytes());

            // Thuật toán Hash sử dụng StringBuilder đơn giản dễ đọc
            String computedChecksum = computeHexChecksum(hashBytes);
            String previewUrl = minioService.getPresignedUrl(objectKey);

            results.add(FileUploadResponse.builder()
                    .fileName(file.getOriginalFilename())
                    .storagePath(objectKey)
                    .payloadChecksum(computedChecksum)
                    .previewUrl(previewUrl)
                    .build());

        }
        return results;
    }

    @Override
    public ExchangeDocumentRequest signAndBuildPayload(SignAndBuildRequest request) throws Exception {

        String effectiveStoragePath = request.getStoragePath();
        String effectiveChecksum = request.getPayloadChecksum();

        // ---- Bước 1: Vẽ dấu trực quan (nếu được yêu cầu) ----
        VisualSignatureRequest stampCoords = request.getStampCoords();
        VisualSignatureRequest signatureCoords = request.getSignatureCoords();
        boolean hasVisual = (stampCoords != null && stampCoords.isApplyVisual())
                || (signatureCoords != null && signatureCoords.isApplyVisual());

        if (hasVisual) {
            log.info("[Simulator-Single] Áp dụng Visual Layers: sender={}", request.getSenderCode());

            // Vẽ dấu + chữ ký → nhận object key file PDF mới
            effectiveStoragePath = visualSignatureService.applyVisualLayers(
                    request.getStoragePath(), request.getSenderCode(),
                    stampCoords, signatureCoords);

            // Tính lại checksum từ file MỚI (đã có dấu)
            try (InputStream newPdfStream = minioService.download(effectiveStoragePath)) {
                byte[] newPdfBytes = newPdfStream.readAllBytes();
                byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(newPdfBytes);
                effectiveChecksum = computeHexChecksum(hashBytes);
            }
            log.info("[Simulator-Single] Visual Layers OK. NewPath={}, NewChecksum={}...",
                    effectiveStoragePath, effectiveChecksum.substring(0, 8));
        }

        // ---- Bước 2: Build ExchangeDocumentRequest ----
        ExchangeDocumentRequest internalRequest = new ExchangeDocumentRequest();
        internalRequest.setSenderCode(request.getSenderCode());
        internalRequest.setReceiverCodes(request.getReceiverCodes());
        internalRequest.setDocumentCode(request.getDocumentCode());
        internalRequest.setPayloadChecksum(effectiveChecksum);
        internalRequest.setStoragePath(effectiveStoragePath);
        internalRequest.setCertificateSerialNumber(request.getCertificateSerialNumber());
        internalRequest.setPriority(request.getPriority() != null ? request.getPriority() : 1);
        internalRequest.setTitle(request.getTitle());
        internalRequest.setDocumentType(request.getDocumentType());
        internalRequest.setSummary(request.getSummary());
        internalRequest.setIssuedDate(request.getIssuedDate());

        String currentTimestamp = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        internalRequest.setTimestamp(currentTimestamp);

        // ---- Bước 3: Ký transport signature ----
        String canonicalString = canonicalStringBuilder.build(internalRequest);
        PrivateKey privateKey = loadPrivateKeyForSender(request.getSenderCode());

        Signature signatureInstance = Signature.getInstance("SHA256withRSA");
        signatureInstance.initSign(privateKey);
        signatureInstance.update(canonicalString.getBytes(StandardCharsets.UTF_8));
        internalRequest.setSignature(Base64.getEncoder().encodeToString(signatureInstance.sign()));

        return internalRequest;
    }

    @Override
    public Object processAndSend(MultipartFile file, String senderCode, List<String> receiverCodes,
            String documentCode, String certificateSerialNumber, Integer priority, String idempotencyKey) throws Exception {

        // 1. Upload & Hash
        String objectKey = minioService.upload(file);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(file.getBytes());
        String computedChecksum = computeHexChecksum(hashBytes);

        // 2. Build Request
        SignAndBuildRequest signReq = new SignAndBuildRequest();
        signReq.setSenderCode(senderCode);
        signReq.setReceiverCodes(receiverCodes);
        signReq.setDocumentCode(documentCode);
        signReq.setStoragePath(objectKey);
        signReq.setPayloadChecksum(computedChecksum);
        signReq.setCertificateSerialNumber(certificateSerialNumber);
        signReq.setPriority(priority);

        // 3. Ký số (không có visual trong flow legacy này)
        ExchangeDocumentRequest finalPayload = signAndBuildPayload(signReq);

        // 4. Gửi sang Gateway (URL từ config, không hardcode)
        log.info("[Simulator] RestClient gửi tới Gateway: {}", gatewayUrl);

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            idempotencyKey = java.util.UUID.randomUUID().toString();
        }

        return restClient.post()
                .uri(gatewayUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .accept(MediaType.APPLICATION_JSON)
                .body(finalPayload)
                .retrieve()
                .body(Object.class);
    }

    // =============================================
    // LUỒNG KÝ NỐI TIẾP (Multi-Signature Sequential)
    // =============================================

    /**
     * Ký nối tiếp: tùy chọn vẽ dấu trực quan trước khi ký transport.
     *
     * <p>
     * <strong>Thứ tự quan trọng</strong>:
     * <ol>
     * <li>Tập hợp existingSignatures từ bước trước</li>
     * <li>Vẽ dấu lên PDF mới (nếu applyVisual == true) → cập nhật storagePath
     * payload</li>
     * <li>Ký transport signature trên payload ĐÃ có storagePath mới</li>
     * </ol>
     */
    @Override
    public MultiSignatureRequest signAndBuildMultiSigPayload(SimulateMultiSigRequest request) throws Exception {
        log.info("[Simulator-MultiSig] Bắt đầu dựng Payload: sender={}, role={}",
                request.getCurrentSenderCode(), request.getSignerRole());

        String currentTimestamp = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        List<SignatureRequest> signatures = new ArrayList<>();
        if (request.getExistingSignatures() != null && !request.getExistingSignatures().isEmpty()) {
            signatures.addAll(request.getExistingSignatures());
            log.info("[Simulator-MultiSig] Nhận {} chữ ký hiện có từ bước trước",
                    request.getExistingSignatures().size());
        }

        int nextOrder = signatures.size() + 1;
        String mockSignatureValue = Base64.getEncoder().encodeToString(
                ("MockPKCS7_Org=" + request.getCurrentSenderCode()
                        + "_Step=" + nextOrder
                        + "_Role=" + request.getSignerRole()
                        + "_TS=" + currentTimestamp).getBytes(StandardCharsets.UTF_8));

        SignatureRequest newSig = SignatureRequest.builder()
                .signatureOrder(nextOrder)
                .signerCode(request.getCurrentSenderCode())
                .signerRole(request.getSignerRole())
                .signatureType(request.getSignatureType() != null ? request.getSignatureType() : "OFFICIAL")
                .certificateSerialNumber(request.getCertificateSerialNumber())
                .timestamp(currentTimestamp)
                .signatureValue(mockSignatureValue)
                .build();
        signatures.add(newSig);

        // ---- Bước 1: Vẽ dấu trực quan (nếu được yêu cầu) ----
        String effectiveStoragePath = request.getStoragePath();
        VisualSignatureRequest stampCoords = request.getStampCoords();
        VisualSignatureRequest signatureCoords = request.getSignatureCoords();
        boolean hasVisual = (stampCoords != null && stampCoords.isApplyVisual())
                || (signatureCoords != null && signatureCoords.isApplyVisual());

        if (hasVisual) {
            log.info("[Simulator-MultiSig] Áp dụng Visual Layers: sender={}",
                    request.getCurrentSenderCode());
            effectiveStoragePath = visualSignatureService.applyVisualLayers(
                    request.getStoragePath(), request.getCurrentSenderCode(),
                    stampCoords, signatureCoords);
            log.info("[Simulator-MultiSig] Visual Layers OK. NewPath={}", effectiveStoragePath);
        }

        // ---- Bước 2: Build MultiSignatureRequest với storagePath đã cập nhật ----
        MultiSignatureRequest payload = new MultiSignatureRequest();
        payload.setMasterTransactionCode(request.getMasterTransactionCode());
        payload.setDocumentCode(request.getDocumentCode());
        payload.setCurrentSenderCode(request.getCurrentSenderCode());
        payload.setRoutingList(request.getRoutingList());
        payload.setDistributionList(request.getDistributionList());
        payload.setStoragePath(effectiveStoragePath); // ← path đã cập nhật (có dấu)
        payload.setRequestTimestamp(currentTimestamp);
        payload.setSignatures(signatures);
        // Metadata truyền xuyên suốt luồng ký nối tiếp
        payload.setTitle(request.getTitle());
        payload.setDocumentType(request.getDocumentType());
        payload.setPriority(request.getPriority());
        payload.setExtractedMetadata(request.getExtractedMetadata());
        payload.setSummary(request.getSummary());
        payload.setIssuedDate(request.getIssuedDate());

        // ---- Bước 3: Ký Transport Layer bằng Private Key của cơ quan ----
        // Transport signature được tính SAU khi storagePath đã là file có dấu
        String canonicalString = canonicalStringBuilder.build(payload);
        log.info("[Simulator-MultiSig] Canonical String:\n{}", canonicalString);

        PrivateKey privateKey = loadPrivateKeyForSender(request.getCurrentSenderCode());

        Signature signatureInstance = Signature.getInstance("SHA256withRSA");
        signatureInstance.initSign(privateKey);
        signatureInstance.update(canonicalString.getBytes(StandardCharsets.UTF_8));
        payload.setTransportSignature(Base64.getEncoder().encodeToString(signatureInstance.sign()));

        log.info("[Simulator-MultiSig] Hoàn tất. Step={}, totalSigs={}, hasVisual={}",
                nextOrder, signatures.size(), hasVisual);
        return payload;
    }

    private PrivateKey loadPrivateKeyForSender(String senderCode) throws Exception {
        String keyFileName = KEYS_CLASSPATH_DIR + senderCode + "_private_key.pem";
        log.info("[Simulator-MultiSig] Tải Private Key: {}", keyFileName);
        try {
            return loadPrivateKeyFromClasspath(keyFileName);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Không tìm thấy Private Key cho [" + senderCode + "]. " +
                            "Kiểm tra file: src/main/resources/" + keyFileName);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "File PEM của [" + senderCode + "] không hợp lệ: " + e.getMessage());
        }
    }

    /**
     * Đọc PEM từ classpath và parse thành PrivateKey (PKCS#8).
     */
    private PrivateKey loadPrivateKeyFromClasspath(String classpathPath) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        String keyContent;
        try (InputStream is = resource.getInputStream()) {
            keyContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        keyContent = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private String computeHexChecksum(byte[] hashBytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Override
    public String getPresignedUrl(String objectKey) {
        return minioService.getPresignedUrl(objectKey);
    }
}

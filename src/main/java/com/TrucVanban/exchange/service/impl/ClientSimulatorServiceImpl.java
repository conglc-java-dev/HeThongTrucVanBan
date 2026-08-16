package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignAndBuildRequest;
import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SimulateMultiSigRequest;
import com.TrucVanban.exchange.dto.response.FileUploadResponse;
import com.TrucVanban.exchange.service.ClientSimulatorService;
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
import org.springframework.util.StringUtils;
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

    /** URL Gateway cho flow 1 chữ ký (legacy). Đọc từ application.yml. */
    @Value("${app.client-simulator.gateway-url:http://localhost:8080/api/v1/exchange}")
    String gatewayUrl;

    /** URL Gateway cho flow đa chữ ký nối tiếp. Đọc từ application.yml. */
    @Value("${app.client-simulator.multi-sig-gateway-url:http://localhost:8080/api/v1/exchange-documents/signatures}")
    String multiSigGatewayUrl;

    /**
     * Thư mục classpath chứa file PEM của các cơ quan.
     * Convention đặt tên: {SENDER_CODE}_private_key.pem
     * Ví dụ: A_BGDDT_private_key.pem, B_BTC_private_key.pem
     */
    private static final String KEYS_CLASSPATH_DIR = "keys/";

    // =============================================
    // UPLOAD
    // =============================================

    @Override
    public List<FileUploadResponse> uploadFiles(MultipartFile[] files) throws Exception {
        if (files == null || files.length == 0) return new ArrayList<>();

        List<FileUploadResponse> results = new ArrayList<>(files.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (MultipartFile file : files) {
            String objectKey = minioService.upload(file);
            byte[] hashBytes = digest.digest(file.getBytes());

            // Thuật toán Hash sử dụng StringBuilder đơn giản dễ đọc
            String computedChecksum = computeHexChecksum(hashBytes);

            results.add(FileUploadResponse.builder()
                    .fileName(file.getOriginalFilename())
                    .storagePath(objectKey)
                    .payloadChecksum(computedChecksum)
                    .build());
        }
        return results;
    }

    @Override
    public ExchangeDocumentRequest signAndBuildPayload(SignAndBuildRequest request) throws Exception {
        ExchangeDocumentRequest internalRequest = new ExchangeDocumentRequest();
        internalRequest.setSenderCode(request.getSenderCode());
        internalRequest.setReceiverCodes(request.getReceiverCodes());
        internalRequest.setDocumentCode(request.getDocumentCode());
        internalRequest.setPayloadChecksum(request.getPayloadChecksum());
        internalRequest.setStoragePath(request.getStoragePath());
        internalRequest.setCertificateSerialNumber(request.getCertificateSerialNumber());
        internalRequest.setPriority(request.getPriority() != null ? request.getPriority() : 1);

        String currentTimestamp = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        internalRequest.setTimestamp(currentTimestamp);

        String canonicalString = canonicalStringBuilder.build(internalRequest);

        // Flow cũ: dùng key mặc định private_key.pem trong classpath root
        PrivateKey privateKey = loadPrivateKeyFromClasspath("keys/private_key.pem");
        PrivateKey privateKey = loadPrivateKeyFromPem();

        Signature signatureInstance = Signature.getInstance("SHA256withRSA");
        signatureInstance.initSign(privateKey);
        signatureInstance.update(canonicalString.getBytes(StandardCharsets.UTF_8));
        byte[] digitalSignatureBytes = signatureInstance.sign();

        internalRequest.setSignature(Base64.getEncoder().encodeToString(digitalSignatureBytes));
        return internalRequest;
    }

    @Override
    public Object processAndSend(MultipartFile file, String senderCode, List<String> receiverCodes,
                                 String documentCode, String certificateSerialNumber, Integer priority,
                                 String idempotencyKey) throws Exception {

        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key must be provided by the caller");
        }

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

        // 3. Ký số
        ExchangeDocumentRequest finalPayload = signAndBuildPayload(signReq);

        // 4. Gửi sang Gateway (URL từ config, không hardcode)
        log.info("[Simulator] RestClient gửi tới Gateway: {}", gatewayUrl);
        return restClient.post()
                .uri(gatewayUrl)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(finalPayload)
                .retrieve()
                .body(Object.class);
    }

    // =============================================
    // FLOW MỚI (Multi-Signature Sequential)
    // =============================================

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

        MultiSignatureRequest payload = new MultiSignatureRequest();
        payload.setMasterTransactionCode(request.getMasterTransactionCode());
        payload.setDocumentCode(request.getDocumentCode());
        payload.setCurrentSenderCode(request.getCurrentSenderCode());
        payload.setRoutingList(request.getRoutingList());
        payload.setDistributionList(request.getDistributionList());
        payload.setStoragePath(request.getStoragePath());
        payload.setRequestTimestamp(currentTimestamp);
        payload.setSignatures(signatures);
        // transportSignature sẽ điền sau khi ký

        // Ký Transport Layer bằng Private Key của cơ quan hiện tại
        String canonicalString = canonicalStringBuilder.build(payload);
        log.info("[Simulator-MultiSig] Canonical String:\n{}", canonicalString);

        PrivateKey privateKey = loadPrivateKeyForSender(request.getCurrentSenderCode());

        Signature signatureInstance = Signature.getInstance("SHA256withRSA");
        signatureInstance.initSign(privateKey);
        signatureInstance.update(canonicalString.getBytes(StandardCharsets.UTF_8));
        payload.setTransportSignature(Base64.getEncoder().encodeToString(signatureInstance.sign()));

        log.info("[Simulator-MultiSig] Hoàn tất. Step={}, totalSigs={}",
                nextOrder, signatures.size());
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
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // Đọc Private Key từ Server
    private PrivateKey loadPrivateKeyFromPem() throws Exception {
        String keyContent = Files.readString(Paths.get(PRIVATE_KEY_PATH));
        keyContent = keyContent.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }
}
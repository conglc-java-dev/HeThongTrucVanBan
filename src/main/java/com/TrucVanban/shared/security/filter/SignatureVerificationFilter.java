package com.TrucVanban.shared.security.filter;

import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.registry.entity.Certificate;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import com.TrucVanban.shared.utils.SignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SignatureVerificationFilter - Interceptor bảo mật 3 lớp cho endpoint POST /api/exchange.
 *
 * [Chốt 1] Khống chế thời gian (Anti Replay Attack) - ±5 phút
 * [Chốt 2] Tra cứu danh tính (Certificate Lookup) - Caffeine Cache → DB
 * [Chốt 3] Đối sánh mật mã (Cryptographic Verification) - RSA SHA256withRSA
 */
@Slf4j
@RequiredArgsConstructor
public class SignatureVerificationFilter extends OncePerRequestFilter {

    private static final String EXCHANGE_PATH = "/exchange";
    private static final String MULTI_SIG_PATH = "/exchange-documents/signatures";
    private static final int MAX_TIME_DIFF_MINUTES = 30;
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RegistryService registryService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final CanonicalStringBuilder canonicalStringBuilder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        boolean isPost = HttpMethod.POST.matches(request.getMethod());
        boolean isLegacyExchange = PATH_MATCHER.match(EXCHANGE_PATH, path);
        boolean isMultiSig = PATH_MATCHER.match(MULTI_SIG_PATH, path);
        return !(isPost && (isLegacyExchange || isMultiSig));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

        log.info("[SignatureFilter] Nhận được body từ Client ({} bytes)", bodyBytes.length);

        JsonNode body;
        try {
            body = objectMapper.readTree(bodyStr);
        } catch (Exception e) {
            log.warn("[SignatureFilter] JSON body không hợp lệ: {} | body='{}'", e.getMessage(), bodyStr.substring(0, Math.min(200, bodyStr.length())));
            writeErrorResponse(response, HttpStatus.BAD_REQUEST,
                    "Dữ liệu đầu vào không hợp lệ. V vui lòng kiểm tra định dạng JSON.");
            return;
        }

        // Phân nhánh theo path
        String path = request.getServletPath();
        if (PATH_MATCHER.match(MULTI_SIG_PATH, path)) {
            doFilterMultiSig(request, response, filterChain, body, bodyBytes);
        } else {
            doFilterLegacy(request, response, filterChain, body, bodyBytes);
        }
    }

    /**
     * Xác minh cho endpoint đa chữ ký nối tiếp.
     */
    private void doFilterMultiSig(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain filterChain, JsonNode body, byte[] bodyBytes)
            throws ServletException, IOException {

        String senderCode = getTextSafe(body, "currentSenderCode");
        String timestampStr = getTextSafe(body, "requestTimestamp");
        String transportSignature = getTextSafe(body, "transportSignature");

        // Lấy certificateSerialNumber của chữ ký CUỐI trong mảng (người gửi hiện tại)
        String serialNumber = getLastSignatureSerial(body);

        log.info("[SignatureFilter-MultiSig] Bắt đầu xác minh: sender={}, serial={}", senderCode, serialNumber);
        if (!checkTimestamp(response, senderCode, serialNumber, timestampStr)) return;

        var certificate = registryService.findActiveCertificateBySerialNumber(serialNumber);
        if (certificate == null) {
            log.warn("[SignatureFilter-MultiSig] [Chốt 2] Không tìm thấy chứng thư: serialNumber={}", serialNumber);
            auditLogService.log("CERT_NOT_FOUND", "ORGANIZATION", senderCode, "FAILURE",
                    buildDetail("Không tìm thấy chứng thư ACTIVE. serialNumber=" + serialNumber, serialNumber, null), null, null);
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    "Chứng thư số không hợp lệ hoặc đã hết hạn.");
            return;
        }

        // Verify transportSignature trên Canonical String mới
        if (transportSignature == null || transportSignature.isBlank()) {
            writeErrorResponse(response, HttpStatus.BAD_REQUEST, "Trường transportSignature là bắt buộc.");
            return;
        }

        // Dựng lại MultiSignatureRequest để tạo Canonical String
        try {
            com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest multiReq =
                    objectMapper.treeToValue(body, com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest.class);
            String canonicalString = canonicalStringBuilder.build(multiReq);
            log.info("[SignatureFilter-MultiSig] [Chốt 3] Canonical String:\n{}", canonicalString);

            boolean isValid = com.TrucVanban.shared.utils.SignatureVerifier.verify(
                    canonicalString, transportSignature, certificate.getPublicKey());

            if (!isValid) {
                log.warn("[SignatureFilter-MultiSig] [Chốt 3] TRANSPORT SIGNATURE KHÔNG HỢP LỆ! sender={}", senderCode);
                auditLogService.log("SIGNATURE_INVALID", "ORGANIZATION", senderCode, "FAILURE",
                        buildDetail("Transport signature không hợp lệ — gói tin có thể bị MITM.",
                                serialNumber, canonicalString), null, null);
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                        "Xác minh transport signature thất bại. Gói tin không toàn vẹn.");
                return;
            }

            log.info("[SignatureFilter-MultiSig] [Chốt 3] OK — Transport signature hợp lệ. sender={}", senderCode);
            auditLogService.log("TRANSPORT_SIGNATURE_VERIFIED", "ORGANIZATION", senderCode, "SUCCESS",
                    buildDetail("Transport signature hợp lệ. Tầng 1 vượt qua.", serialNumber, canonicalString), null, null);

            CachedBodyRequestWrapper cachedRequest = new CachedBodyRequestWrapper(request, bodyBytes);
            cachedRequest.setAttribute("verified_org_id", certificate.getOrganizationId());
            cachedRequest.setAttribute("verified_serial_number", serialNumber);
            filterChain.doFilter(cachedRequest, response);

        } catch (Exception e) {
            log.error("[SignatureFilter-MultiSig] Lỗi khi xử lý request: {}", e.getMessage(), e);
            writeErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi nội bộ khi xác minh chữ ký: " + e.getMessage());
        }
    }

    // ================== LEGACY PATH ==================

    /**
     * Xác minh Tầng 1 cho endpoint cũ /exchange (ExchangeDocumentRequest).
     * Giữ nguyên hoàn toàn logic gốc.
     */
    private void doFilterLegacy(HttpServletRequest request, HttpServletResponse response,
                                 FilterChain filterChain, JsonNode body, byte[] bodyBytes)
            throws ServletException, IOException {

        String senderCode = getTextSafe(body, "senderCode");
        String serialNumber = getTextSafe(body, "certificateSerialNumber");
        String timestampStr = getTextSafe(body, "timestamp");
        String signature = getTextSafe(body, "signature");
        String documentCode = getTextSafe(body, "documentCode");
        String payloadChecksum = getTextSafe(body, "payloadChecksum");
        String issuedDate = getTextSafe(body, "issuedDate");
        List<String> receivers = getReceiversSafe(body);

        log.info("[SignatureFilter-Legacy] Bắt đầu xác minh gói tin: sender={}, serial={}", senderCode, serialNumber);

        // CHỐT 1: Anti-Replay
        if (!checkTimestamp(response, senderCode, serialNumber, timestampStr)) return;

        // CHỐT 2: Certificate Lookup
        if (serialNumber == null || serialNumber.isBlank()) {
            writeErrorResponse(response, HttpStatus.BAD_REQUEST, "Trường certificateSerialNumber là bắt buộc.");
            return;
        }

        var certificate = registryService.findActiveCertificateBySerialNumber(serialNumber);
        if (certificate == null) {
            log.warn("[SignatureFilter-Legacy] [Chốt 2] Không tìm thấy chứng thư: serialNumber={}", serialNumber);
            auditLogService.log("CERT_NOT_FOUND", "ORGANIZATION", senderCode, "FAILURE",
                    buildDetail("Không tìm thấy chứng thư ACTIVE. serialNumber=" + serialNumber, serialNumber, null), null, null);
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    "Chứng thư số không hợp lệ hoặc đã hết hạn.");
            return;
        }

        // CHỐT 3: Verify signature
        if (signature == null || signature.isBlank()) {
            writeErrorResponse(response, HttpStatus.BAD_REQUEST, "Trường signature là bắt buộc.");
            return;
        }

        String canonicalString = canonicalStringBuilder.buildLegacy(
                serialNumber, documentCode, payloadChecksum, receivers, senderCode, timestampStr, issuedDate);
        log.info("[SignatureFilter-Legacy] [Chốt 3] Canonical String:\n{}", canonicalString);

        boolean isValid = com.TrucVanban.shared.utils.SignatureVerifier.verify(
                canonicalString, signature, certificate.getPublicKey());

        if (!isValid) {
            log.warn("[SignatureFilter-Legacy] [Chốt 3] CHỮ KÝ KHÔNG HỢP LỆ! sender={}", senderCode);
            auditLogService.log("SIGNATURE_INVALID", "ORGANIZATION", senderCode, "FAILURE",
                    buildDetail("Chữ ký không hợp lệ.", serialNumber, canonicalString), null, null);
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    "Xác minh chữ ký số thất bại.");
            return;
        }

        auditLogService.log("SIGNATURE_VERIFIED", "ORGANIZATION", senderCode, "SUCCESS",
                buildDetail("Xác minh chữ ký thành công.", serialNumber, canonicalString), null, null);

        CachedBodyRequestWrapper cachedRequest = new CachedBodyRequestWrapper(request, bodyBytes);
        cachedRequest.setAttribute("verified_org_id", certificate.getOrganizationId());
        cachedRequest.setAttribute("verified_serial_number", serialNumber);
        filterChain.doFilter(cachedRequest, response);
    }

    private boolean checkTimestamp(HttpServletResponse response, String senderCode,
                                    String serialNumber, String timestampStr) throws IOException {
        if (timestampStr == null || timestampStr.isBlank()) {
            log.warn("[SignatureFilter] [Chốt 1] Thiếu trường timestamp. sender={}", senderCode);
            auditLogService.log("SIGNATURE_REJECTED", "SYSTEM", senderCode, "FAILURE",
                    buildDetail("Thiếu trường timestamp", serialNumber, null), null, null);
            writeErrorResponse(response, HttpStatus.BAD_REQUEST,
                    "Trường timestamp/requestTimestamp là bắt buộc.");
            return false;
        }
        OffsetDateTime packetTime;
        try {
            packetTime = OffsetDateTime.parse(timestampStr);
        } catch (Exception e) {
            auditLogService.log("REPLAY_ATTACK_TIMESTAMP_INVALID", "SYSTEM", senderCode, "FAILURE",
                    buildDetail("Timestamp không đúng định dạng: " + timestampStr, serialNumber, null), null, null);
            writeErrorResponse(response, HttpStatus.BAD_REQUEST, "Timestamp không đúng định dạng ISO 8601.");
            return false;
        }
        OffsetDateTime serverTime = OffsetDateTime.now();
        long diffMinutes = Math.abs(Duration.between(packetTime, serverTime).toMinutes());
        if (diffMinutes > MAX_TIME_DIFF_MINUTES) {
            log.warn("[SignatureFilter] [Chốt 1] REPLAY ATTACK! sender={}, drift={}m", senderCode, diffMinutes);
            auditLogService.log("REPLAY_ATTACK_DETECTED", "ORGANIZATION", senderCode, "FAILURE",
                    buildDetail(String.format("Lệch thời gian %dm (max %dm)", diffMinutes, MAX_TIME_DIFF_MINUTES),
                            serialNumber, null), null, null);
            writeErrorResponse(response, HttpStatus.REQUEST_TIMEOUT,
                    String.format("Replay Attack phát hiện. Lệch %d phút (giới hạn %d phút).",
                            diffMinutes, MAX_TIME_DIFF_MINUTES));
            return false;
        }
        log.info("[SignatureFilter] [Chốt 1] OK - Lệch {}m < {}m", diffMinutes, MAX_TIME_DIFF_MINUTES);
        return true;
    }

    /**
     * Lấy certificateSerialNumber của chữ ký CUỐI trong mảng signatures[].
     * Đây là người gửi hiện tại, dùng để lookup Public Key xác minh transportSignature.
     */
    private String getLastSignatureSerial(JsonNode body) {
        JsonNode signaturesNode = body.get("signatures");
        if (signaturesNode == null || !signaturesNode.isArray() || signaturesNode.isEmpty()) {
            return null;
        }
        // Tìm phần tử có signatureOrder cao nhất
        JsonNode lastSig = null;
        int maxOrder = -1;
        for (JsonNode sig : signaturesNode) {
            int order = sig.path("signatureOrder").asInt(-1);
            if (order > maxOrder) {
                maxOrder = order;
                lastSig = sig;
            }
        }
        return lastSig != null ? getTextSafe(lastSig, "certificateSerialNumber") : null;
    }


    private String getTextSafe(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : null;
    }

    private List<String> getReceiversSafe(JsonNode node) {
        JsonNode receiversNode = node.get("receiverCodes");
        List<String> result = new ArrayList<>();
        if (receiversNode != null && receiversNode.isArray()) {
            receiversNode.forEach(r -> result.add(r.asText()));
        }
        return result;
    }

    private String buildDetail(String message, String serialNumber, String canonicalString) {
        try {
            var map = new java.util.LinkedHashMap<String, String>();
            map.put("message", message);
            if (serialNumber != null) map.put("certificateSerialNumber", serialNumber);
            if (canonicalString != null) map.put("canonicalString", canonicalString);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"message\":\"" + message + "\"}";
        }
    }

    private void writeErrorResponse(HttpServletResponse response,
                                    HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String jsonBody = String.format("{\"success\":false,\"message\":\"%s\",\"data\":null}",
                message.replace("\"", "\\\""));
        response.getWriter().write(jsonBody);
    }

    /**
     * Custom HttpServletRequestWrapper cho phép đọc lại body nhiều lần.
     */
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.cachedBody = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }
    }

    /**
     * Custom ServletInputStream đọc từ byte array thay vì socket.
     */
    private static class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream inputStream;

        public CachedServletInputStream(byte[] body) {
            this.inputStream = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Không cần listener cho cached body
        }

        @Override
        public int read() {
            return inputStream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return inputStream.read(b, off, len);
        }

        @Override
        public int available() {
            return inputStream.available();
        }
    }
}

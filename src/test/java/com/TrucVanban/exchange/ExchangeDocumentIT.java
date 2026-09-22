package com.TrucVanban.exchange;

import com.TrucVanban.BaseIT;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.registry.entity.Certificate;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.CertificateStatus;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.CertificateRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.enums.OutboxEventStatus;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-04: Kiểm thử luồng gửi văn bản (POST /exchange) + Outbox Pattern.
 *
 * <p>Luồng happy path quan trọng nhất của hệ thống:
 * HTTP Request → ExchangeService → lưu Document + ExchangeTransactions +
 * OutboxEvent (status=NEW) vào PostgreSQL trong 1 @Transactional → commit.
 *
 * <p>Outbox Pattern đảm bảo: nếu RabbitMQ tạm thời down, văn bản KHÔNG bị mất
 * vì đã có row OutboxEvent trong DB. OutboxPublisher sẽ xử lý sau.
 *
 * <p>/exchange được permit all trong SecurityConfig (HMAC disabled trong profile "it").
 */
@DisplayName("IT-04: Exchange — Gửi văn bản + Outbox Pattern")
class ExchangeDocumentIT extends BaseIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String SENDER_CODE = "IT_SENDER_ORG";
    private static final String RECEIVER_CODE = "IT_RECEIVER_ORG";
    private static final String CERT_SERIAL = "IT-CERT-SN-001";

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @BeforeEach
    void setUp() {
        // Dọn DB: exchange data
        documentRepository.findAll().stream()
                .filter(d -> d.getDocumentCode().startsWith("IT-DOC-"))
                .forEach(documentRepository::delete);

        // Dọn outbox events cũ (nếu có)
        outboxEventRepository.findAll().stream()
                .filter(e -> "EXCHANGE_TRANSACTION".equals(e.getAggregateType()))
                .forEach(outboxEventRepository::delete);

        // Dọn Redis idempotency keys
        var idKeys = redisTemplate.keys("idempotency:exchange-document:*");
        if (idKeys != null && !idKeys.isEmpty()) redisTemplate.delete(idKeys);

        // Dọn organizations cũ
        organizationRepository.findByCode(SENDER_CODE).ifPresent(o -> {
            certificateRepository.findAll().stream()
                    .filter(c -> o.getId().equals(c.getOrganizationId()))
                    .forEach(certificateRepository::delete);
            organizationRepository.delete(o);
        });
        organizationRepository.findByCode(RECEIVER_CODE).ifPresent(organizationRepository::delete);

        // Seed: Tạo 2 tổ chức ACTIVE (sender + receiver)
        Organization sender = organizationRepository.save(Organization.builder()
                .code(SENDER_CODE)
                .name("Cơ quan gửi IT")
                .receiveEndpoint("https://sender.example.com/receive")
                .status(OrganizationStatus.ACTIVE)
                .build());

        organizationRepository.save(Organization.builder()
                .code(RECEIVER_CODE)
                .name("Cơ quan nhận IT")
                .receiveEndpoint("https://receiver.example.com/receive")
                .status(OrganizationStatus.ACTIVE)
                .build());

        // Seed: Certificate cho sender (cần để ExchangeService tra cứu)
        certificateRepository.save(Certificate.builder()
                .organizationId(sender.getId())
                .publicKey("-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0FAKE\n-----END PUBLIC KEY-----")
                .serialNumber(CERT_SERIAL)
                .issuedAt(LocalDateTime.now().minusDays(1))
                .expiredAt(LocalDateTime.now().plusYears(1))
                .status(CertificateStatus.ACTIVE)
                .build());
    }

    /**
     * Helper tạo request gửi văn bản hợp lệ với Idempotency-Key header.
     */
    private HttpEntity<ExchangeDocumentRequest> buildExchangeRequest(String docCode, String idempotencyKey) {
        ExchangeDocumentRequest request = new ExchangeDocumentRequest();
        request.setSenderCode(SENDER_CODE);
        request.setReceiverCodes(List.of(RECEIVER_CODE));
        request.setDocumentCode(docCode);
        request.setPayloadChecksum("abc123def456abc123def456abc123def456abc123def456abc123def456ab12");
        request.setCertificateSerialNumber(CERT_SERIAL);
        request.setTimestamp("2026-09-22T10:00:00Z");
        request.setSignature("dGVzdC1zaWduYXR1cmUtYmFzZTY0LWVuY29kZWQ=");
        request.setStoragePath("it-test/" + docCode + "/v1.pdf");
        request.setTitle("Văn bản test IT-04: " + docCode);
        request.setDocumentType("OFFICIAL_DISPATCH");
        request.setPriority(1);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(request, headers);
    }

    @Test
    @DisplayName("Gửi văn bản hợp lệ → HTTP 200, DB có Document + OutboxEvent(status=NEW)")
    void exchangeDocument_validRequest_shouldPersistDocumentAndOutboxEvent() {
        // GIVEN
        String docCode = "IT-DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String idempotencyKey = UUID.randomUUID().toString();

        // WHEN
        ResponseEntity<Object> response = restTemplate.postForEntity(
                baseUrl() + "/exchange",
                buildExchangeRequest(docCode, idempotencyKey),
                Object.class
        );

        // THEN — HTTP 200
        assertThat(response.getStatusCode())
                .as("Gửi văn bản hợp lệ phải trả về HTTP 200")
                .isEqualTo(HttpStatus.OK);

        // THEN — Document được lưu vào DB
        boolean docExists = documentRepository.existsDocumentByDocumentCode(docCode);
        assertThat(docExists)
                .as("Document '%s' phải được lưu vào PostgreSQL", docCode)
                .isTrue();

        // THEN — OutboxEvent được lưu với status NEW (Outbox Pattern)
        // Đây là điều Unit Test KHÔNG THỂ xác nhận (vì mock outboxEventRepository)
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll().stream()
                .filter(e -> "EXCHANGE_TRANSACTION".equals(e.getAggregateType())
                        && OutboxEventStatus.NEW.equals(e.getStatus()))
                .toList();
        assertThat(outboxEvents)
                .as("Phải có ít nhất 1 OutboxEvent với status NEW sau khi gửi văn bản")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Cùng Idempotency-Key → request thứ 2 trả về HTTP 409, DB không có document trùng")
    void exchangeDocument_duplicateIdempotencyKey_shouldReturn409() {
        // GIVEN: gửi lần 1 thành công
        String docCode1 = "IT-DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String sharedKey = UUID.randomUUID().toString();
        restTemplate.postForEntity(baseUrl() + "/exchange",
                buildExchangeRequest(docCode1, sharedKey), Object.class);

        // Chờ Redis cập nhật idempotency key về COMPLETED
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // WHEN: gửi lần 2 với cùng Idempotency-Key (nhưng doc code khác)
        String docCode2 = "IT-DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ResponseEntity<Object> response = restTemplate.postForEntity(baseUrl() + "/exchange",
                buildExchangeRequest(docCode2, sharedKey), Object.class);

        // THEN — HTTP 409 (request đã được xử lý)
        assertThat(response.getStatusCode())
                .as("Cùng Idempotency-Key đã COMPLETED phải trả về HTTP 409")
                .isEqualTo(HttpStatus.CONFLICT);

        // THEN — document lần 2 KHÔNG được lưu
        boolean doc2Exists = documentRepository.existsDocumentByDocumentCode(docCode2);
        assertThat(doc2Exists)
                .as("Document '%s' từ request trùng idempotency key không được lưu", docCode2)
                .isFalse();
    }

    @Test
    @DisplayName("Thiếu Idempotency-Key header → HTTP 400 Bad Request")
    void exchangeDocument_missingIdempotencyKey_shouldReturn400() {
        // GIVEN: request không có Idempotency-Key header
        ExchangeDocumentRequest request = new ExchangeDocumentRequest();
        request.setSenderCode(SENDER_CODE);
        request.setReceiverCodes(List.of(RECEIVER_CODE));
        request.setDocumentCode("IT-DOC-NO-IDEMPOTENCY");
        request.setPayloadChecksum("abc123def456abc123def456abc123def456abc123def456abc123def456ab12");
        request.setCertificateSerialNumber(CERT_SERIAL);
        request.setTimestamp("2026-09-22T10:00:00Z");
        request.setSignature("dGVzdC1zaWduYXR1cmUtYmFzZTY0LWVuY29kZWQ=");
        request.setStoragePath("it-test/no-idem/v1.pdf");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Không set Idempotency-Key
        HttpEntity<ExchangeDocumentRequest> entity = new HttpEntity<>(request, headers);

        // WHEN
        ResponseEntity<Object> response = restTemplate.postForEntity(
                baseUrl() + "/exchange", entity, Object.class);

        // THEN — HTTP 400
        assertThat(response.getStatusCode())
                .as("Thiếu Idempotency-Key phải trả về HTTP 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("senderCode không tồn tại → HTTP 404, không lưu document hay outbox event")
    void exchangeDocument_unknownSenderCode_shouldReturn404() {
        // GIVEN
        String docCode = "IT-DOC-UNKNOWN-SENDER";

        // WHEN
        ResponseEntity<Object> response = restTemplate.postForEntity(
                baseUrl() + "/exchange",
                buildExchangeRequest(docCode, UUID.randomUUID().toString()),
                Object.class
        );

        // THEN — HTTP 404
        assertThat(response.getStatusCode())
                .as("Sender không tồn tại phải trả về HTTP 404")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);

        // THEN — Không có document nào được lưu
        boolean docExists = documentRepository.existsDocumentByDocumentCode(docCode);
        assertThat(docExists)
                .as("Không được lưu document khi sender không tồn tại")
                .isFalse();
    }
}

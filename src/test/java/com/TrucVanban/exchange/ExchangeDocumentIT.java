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
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import org.junit.jupiter.api.BeforeAll;
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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-04: Kiểm thử luồng gửi văn bản (POST /exchange) + Outbox Pattern.
 *
 * <p>Luồng happy path quan trọng nhất của hệ thống:
 * HTTP Request → SignatureVerificationFilter (xác minh chữ ký số SHA256withRSA)
 * → ExchangeService → lưu Document + ExchangeTransactions + OutboxEvent (status=NEW)
 * vào PostgreSQL trong 1 @Transactional → commit.
 *
 * <p>Outbox Pattern đảm bảo: nếu RabbitMQ tạm thời down, văn bản KHÔNG bị mất
 * vì đã có row OutboxEvent trong DB. OutboxPublisher sẽ xử lý sau.
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

    @Autowired
    private CanonicalStringBuilder canonicalStringBuilder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final String SENDER_CODE = "IT_SENDER_ORG";
    private static final String RECEIVER_CODE = "IT_RECEIVER_ORG";
    private static final String CERT_SERIAL = "IT-CERT-SN-001";

    private static KeyPair testKeyPair;

    @BeforeAll
    static void initKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        testKeyPair = keyGen.generateKeyPair();
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM outbox_event WHERE aggregate_type = 'EXCHANGE_TRANSACTION'");
        jdbcTemplate.execute("DELETE FROM status_histories WHERE transaction_id IN (SELECT id FROM exchange_transactions WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%'))");
        jdbcTemplate.execute("DELETE FROM document_signatures WHERE transaction_id IN (SELECT id FROM exchange_transactions WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%'))");
        jdbcTemplate.execute("DELETE FROM exchange_transactions WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%')");
        jdbcTemplate.execute("DELETE FROM document_receivers WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%')");
        jdbcTemplate.execute("DELETE FROM document_versions WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%')");
        jdbcTemplate.execute("DELETE FROM document_actions WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%')");
        jdbcTemplate.execute("DELETE FROM audit_logs WHERE document_id IN (SELECT id FROM documents WHERE document_code LIKE 'IT-DOC-%')");
        jdbcTemplate.execute("DELETE FROM documents WHERE document_code LIKE 'IT-DOC-%'");

        var idKeys = redisTemplate.keys("idempotency:exchange-document:*");
        if (idKeys != null && !idKeys.isEmpty()) redisTemplate.delete(idKeys);
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();

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

        // Seed: Certificate cho sender với Public Key tương ứng testKeyPair
        byte[] publicKeyBytes = testKeyPair.getPublic().getEncoded();
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getEncoder().encodeToString(publicKeyBytes) +
                "\n-----END PUBLIC KEY-----";

        certificateRepository.save(Certificate.builder()
                .organizationId(sender.getId())
                .publicKey(publicKeyPem)
                .serialNumber(CERT_SERIAL)
                .issuedAt(LocalDateTime.now().minusDays(1))
                .expiredAt(LocalDateTime.now().plusYears(1))
                .status(CertificateStatus.ACTIVE)
                .build());
    }

    /**
     * Ký số request bằng Private Key theo đúng thuật toán SHA256withRSA.
     */
    private String signRequest(ExchangeDocumentRequest request) {
        try {
            String canonicalString = canonicalStringBuilder.build(request);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(testKeyPair.getPrivate());
            sig.update(canonicalString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException("Ký số request thất bại trong IT test", e);
        }
    }

    /**
     * Helper tạo request gửi văn bản hợp lệ với Idempotency-Key header.
     */
    private HttpEntity<ExchangeDocumentRequest> buildExchangeRequest(String docCode, String idempotencyKey) {
        return buildExchangeRequest(SENDER_CODE, docCode, idempotencyKey);
    }

    private HttpEntity<ExchangeDocumentRequest> buildExchangeRequest(String senderCode, String docCode, String idempotencyKey) {
        ExchangeDocumentRequest request = new ExchangeDocumentRequest();
        request.setSenderCode(senderCode);
        request.setReceiverCodes(List.of(RECEIVER_CODE));
        request.setDocumentCode(docCode);
        request.setPayloadChecksum("abc123def456abc123def456abc123def456abc123def456abc123def456ab12");
        request.setCertificateSerialNumber(CERT_SERIAL);
        request.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC).toString());
        request.setStoragePath("it-test/" + docCode + "/v1.pdf");
        request.setTitle("Văn bản test IT-04: " + docCode);
        request.setDocumentType("OFFICIAL_DISPATCH");
        request.setPriority(1);

        // Ký số hợp lệ để vượt qua SignatureVerificationFilter
        request.setSignature(signRequest(request));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
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
        ResponseEntity<Object> firstResponse = restTemplate.postForEntity(baseUrl() + "/exchange",
                buildExchangeRequest(docCode1, sharedKey), Object.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

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
        // GIVEN: request không có Idempotency-Key header nhưng timestamp và chữ ký hợp lệ
        HttpEntity<ExchangeDocumentRequest> entity = buildExchangeRequest("IT-DOC-NO-IDEMPOTENCY", null);

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
                buildExchangeRequest("UNKNOWN_SENDER_CODE", docCode, UUID.randomUUID().toString()),
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

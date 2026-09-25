package com.TrucVanban.registry;

import com.TrucVanban.BaseIT;
import com.TrucVanban.registry.dto.request.CertificateRequest;
import com.TrucVanban.registry.dto.request.RegisterOrganizationRequest;
import com.TrucVanban.registry.dto.request.UpdateOrganizationStatusRequest;
import com.TrucVanban.registry.entity.ApiKey;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.ApiKeyStatus;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.ApiKeyRepository;
import com.TrucVanban.registry.repository.CertificateRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.infrastructure.security.hmac.AesGcmEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-02: Kiểm thử vòng đời đăng ký tổ chức — luồng tích hợp từ HTTP → Service → PostgreSQL → Redis.
 *
 * <p>/registry/** được permit all trong SecurityConfig nên không cần JWT.
 *
 * <p>Dữ liệu được dọn sạch thủ công trong @BeforeEach vì TestRestTemplate gọi qua HTTP thật,
 * transaction của request commit trước khi @Rollback test có thể can thiệp.
 */
@DisplayName("IT-02: Registry — Vòng đời đăng ký tổ chức")
class OrganizationRegistrationIT extends BaseIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AesGcmEncryptionService aesGcmEncryptionService;

    private static final String TEST_ORG_CODE = "IT_TEST_ORG_01";

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @BeforeEach
    void cleanUp() {
        // Dọn sạch org test + dữ liệu liên quan để mỗi test độc lập
        organizationRepository.findByCode(TEST_ORG_CODE).ifPresent(org -> {
            // Xóa certificate (dùng findAll + filter vì repo không có findByOrganizationId)
            certificateRepository.findAll().stream()
                    .filter(c -> org.getId().equals(c.getOrganizationId()))
                    .forEach(certificateRepository::delete);
            // Xóa API key
            apiKeyRepository.findAll().stream()
                    .filter(k -> org.getId().equals(k.getAgencyId()))
                    .forEach(apiKeyRepository::delete);
            organizationRepository.delete(org);
        });
        // Dọn Redis cache test
        var keys = redisTemplate.keys("apikey:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        var agencyKeys = redisTemplate.keys("agency:keys:*");
        if (agencyKeys != null && !agencyKeys.isEmpty()) redisTemplate.delete(agencyKeys);
    }

    /** Helper tạo request hợp lệ */
    private RegisterOrganizationRequest buildValidRequest(String code) {
        CertificateRequest cert = new CertificateRequest();
        cert.setPublicKey("-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0FAKE\n-----END PUBLIC KEY-----");
        cert.setSerialNumber("SN-IT-001");
        cert.setIssuedAt(LocalDateTime.now().minusDays(1));
        cert.setExpiredAt(LocalDateTime.now().plusYears(1));

        RegisterOrganizationRequest request = new RegisterOrganizationRequest();
        request.setCode(code);
        request.setName("Cơ quan test " + code);
        request.setReceiveEndpoint("https://agency-test.example.com/receive");
        request.setCertificate(cert);
        return request;
    }

    // =========================================================================
    //  Nhóm 1: Đăng ký tổ chức mới
    // =========================================================================
    @Nested
    @DisplayName("Đăng ký tổ chức mới")
    class RegisterOrganization {

        @Test
        @DisplayName("Đăng ký hợp lệ → HTTP 201, lưu org + certificate vào PostgreSQL với status PENDING_APPROVAL")
        void registerOrganization_validRequest_shouldPersistOrgAndCertificate() {
            // GIVEN
            RegisterOrganizationRequest request = buildValidRequest(TEST_ORG_CODE);

            // WHEN
            ResponseEntity<Object> response = restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations", request, Object.class);

            // THEN — HTTP 201
            assertThat(response.getStatusCode())
                    .as("Phải trả về HTTP 201 Created")
                    .isEqualTo(HttpStatus.CREATED);

            // THEN — Org trong DB với status PENDING_APPROVAL
            Organization savedOrg = organizationRepository.findByCode(TEST_ORG_CODE).orElse(null);
            assertThat(savedOrg).as("Organization phải được lưu vào DB").isNotNull();
            assertThat(savedOrg.getStatus())
                    .as("Status mặc định phải là PENDING_APPROVAL")
                    .isEqualTo(OrganizationStatus.PENDING_APPROVAL);

            // THEN — 1 Certificate được lưu kèm
            long certCount = certificateRepository.findAll().stream()
                    .filter(c -> savedOrg.getId().equals(c.getOrganizationId()))
                    .count();
            assertThat(certCount)
                    .as("Phải có đúng 1 certificate được lưu")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("Đăng ký trùng code → HTTP 409 Conflict, DB vẫn chỉ có 1 org")
        void registerOrganization_duplicateCode_shouldReturn409() {
            // GIVEN: đã đăng ký lần 1
            restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // WHEN: đăng ký lần 2 với cùng code
            ResponseEntity<Object> response = restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // THEN — HTTP 409
            assertThat(response.getStatusCode())
                    .as("Phải trả về HTTP 409 Conflict khi trùng mã tổ chức")
                    .isEqualTo(HttpStatus.CONFLICT);

            // THEN — DB chỉ có đúng 1 org
            long orgCount = organizationRepository.findAll().stream()
                    .filter(o -> TEST_ORG_CODE.equals(o.getCode()))
                    .count();
            assertThat(orgCount)
                    .as("DB phải chỉ có đúng 1 organization với code này")
                    .isEqualTo(1L);
        }
    }

    // =========================================================================
    //  Nhóm 2: Cập nhật trạng thái tổ chức
    // =========================================================================
    @Nested
    @DisplayName("Cập nhật trạng thái tổ chức")
    class UpdateOrganizationStatus {

        @Test
        @DisplayName("Suspend tổ chức (ACTIVE → SUSPENDED) → DB status = SUSPENDED, Redis cache API key bị evict")
        void updateStatus_suspend_shouldChangeStatusAndEvictRedisCache() {
            // GIVEN: tạo org (PENDING_APPROVAL)
            restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // GIVEN: approve org sang ACTIVE
            UpdateOrganizationStatusRequest approveReq = new UpdateOrganizationStatusRequest();
            approveReq.setStatus(OrganizationStatus.ACTIVE);
            restTemplate.exchange(
                    baseUrl() + "/registry/organizations/" + TEST_ORG_CODE + "/status",
                    HttpMethod.PATCH, new HttpEntity<>(approveReq), Object.class);

            Organization org = organizationRepository.findByCode(TEST_ORG_CODE).orElseThrow();

            // GIVEN: seed API key vào DB + Redis để giả lập có cache tồn tại
            String encryptedSecret = aesGcmEncryptionService.encrypt("test-secret-key-1234567");
            ApiKey apiKey = ApiKey.builder()
                    .keyId("IT_KEY_001")
                    .secretEnc(encryptedSecret)
                    .secretHint("test")
                    .algorithm("HmacSHA256")
                    .agencyId(org.getId())
                    .status(ApiKeyStatus.ACTIVE)
                    .expiresAt(OffsetDateTime.now().plusYears(1))
                    .build();
            apiKeyRepository.save(apiKey);
            redisTemplate.opsForValue().set("apikey:IT_KEY_001", "{\"keyId\":\"IT_KEY_001\"}");
            redisTemplate.opsForSet().add("agency:keys:" + org.getId(), "IT_KEY_001");

            // WHEN: suspend tổ chức (ACTIVE -> SUSPENDED kích hoạt evict cache)
            UpdateOrganizationStatusRequest suspendReq = new UpdateOrganizationStatusRequest();
            suspendReq.setStatus(OrganizationStatus.SUSPENDED);
            suspendReq.setReason("Tạm đình chỉ hoạt động để kiểm thử cache eviction");
            restTemplate.exchange(
                    baseUrl() + "/registry/organizations/" + TEST_ORG_CODE + "/status",
                    HttpMethod.PATCH, new HttpEntity<>(suspendReq), Object.class);

            // THEN — DB status = SUSPENDED
            Organization updated = organizationRepository.findByCode(TEST_ORG_CODE).orElseThrow();
            assertThat(updated.getStatus())
                    .as("Trạng thái trong DB phải là SUSPENDED sau khi suspend")
                    .isEqualTo(OrganizationStatus.SUSPENDED);

            // THEN — Redis cache đã bị evict (evictAgencyCache chạy khi status là SUSPENDED/REJECTED)
            Boolean cacheExists = redisTemplate.hasKey("apikey:IT_KEY_001");
            assertThat(cacheExists)
                    .as("Redis cache 'apikey:IT_KEY_001' phải bị xóa sau khi evict cache tổ chức")
                    .isFalse();
        }

        @Test
        @DisplayName("PENDING_APPROVAL → SUSPENDED trực tiếp → HTTP 409 (vi phạm state machine)")
        void updateStatus_invalidTransition_shouldReturn409() {
            // GIVEN: org đang ở PENDING_APPROVAL
            restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // WHEN: cố chuyển thẳng sang SUSPENDED
            UpdateOrganizationStatusRequest updateReq = new UpdateOrganizationStatusRequest();
            updateReq.setStatus(OrganizationStatus.SUSPENDED);
            updateReq.setReason("Vi phạm state machine test");
            ResponseEntity<Object> response = restTemplate.exchange(
                    baseUrl() + "/registry/organizations/" + TEST_ORG_CODE + "/status",
                    HttpMethod.PATCH, new HttpEntity<>(updateReq), Object.class);

            // THEN — HTTP 409 Conflict (BusinessLogicException được GlobalException map sang 409)
            assertThat(response.getStatusCode())
                    .as("Vi phạm state machine phải trả về HTTP 409 Conflict")
                    .isEqualTo(HttpStatus.CONFLICT);

            // THEN — Status trong DB không thay đổi
            Organization unchanged = organizationRepository.findByCode(TEST_ORG_CODE).orElseThrow();
            assertThat(unchanged.getStatus())
                    .as("Status trong DB phải vẫn là PENDING_APPROVAL, không bị thay đổi")
                    .isEqualTo(OrganizationStatus.PENDING_APPROVAL);
        }
    }
}

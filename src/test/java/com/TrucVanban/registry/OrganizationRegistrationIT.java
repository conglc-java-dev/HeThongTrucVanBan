package com.TrucVanban.registry;

import com.TrucVanban.BaseIT;
import com.TrucVanban.registry.dto.request.CertificateRequest;
import com.TrucVanban.registry.dto.request.RegisterOrganizationRequest;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.CertificateRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-02: Kiem thu tao co quan — luong tich hop tu HTTP → Service → PostgreSQL.
 *
 * <p>/registry/** duoc permit all trong SecurityConfig nen khong can JWT.
 *
 * <p>Du lieu duoc don sach thu cong trong @BeforeEach vi TestRestTemplate goi qua HTTP that,
 * transaction cua request commit truoc khi @Rollback test co the can thiep.
 */
@DisplayName("IT-02: Registry — Tao co quan")
class OrganizationRegistrationIT extends BaseIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private CertificateRepository certificateRepository;

    private static final String TEST_ORG_CODE = "IT_TEST_ORG_01";

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @BeforeEach
    void cleanUp() {
        organizationRepository.findByCode(TEST_ORG_CODE).ifPresent(org -> {
            certificateRepository.findAll().stream()
                    .filter(c -> org.getId().equals(c.getOrganizationId()))
                    .forEach(certificateRepository::delete);
            organizationRepository.delete(org);
        });
    }

    /** Helper tao request hop le */
    private RegisterOrganizationRequest buildValidRequest(String code) {
        CertificateRequest cert = new CertificateRequest();
        cert.setPublicKey("-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0FAKE\n-----END PUBLIC KEY-----");
        cert.setSerialNumber("SN-IT-001");
        cert.setIssuedAt(LocalDateTime.now().minusDays(1));
        cert.setExpiredAt(LocalDateTime.now().plusYears(1));

        RegisterOrganizationRequest request = new RegisterOrganizationRequest();
        request.setCode(code);
        request.setName("Co quan test " + code);
        request.setReceiveEndpoint("https://agency-test.example.com/receive");
        request.setCertificate(cert);
        return request;
    }

    // =========================================================================
    //  Nhom 1: Tao co quan moi
    // =========================================================================
    @Nested
    @DisplayName("Tao co quan moi")
    class RegisterOrganization {

        @Test
        @DisplayName("Tao hop le -> HTTP 201, luu org + certificate vao PostgreSQL voi status ACTIVE")
        void registerOrganization_validRequest_shouldPersistOrgAndCertificate() {
            // GIVEN
            RegisterOrganizationRequest request = buildValidRequest(TEST_ORG_CODE);

            // WHEN
            ResponseEntity<Object> response = restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations", request, Object.class);

            // THEN - HTTP 201
            assertThat(response.getStatusCode())
                    .as("Phai tra ve HTTP 201 Created")
                    .isEqualTo(HttpStatus.CREATED);

            // THEN - Org trong DB voi status ACTIVE (admin tu tao, khong can approval)
            Organization savedOrg = organizationRepository.findByCode(TEST_ORG_CODE).orElse(null);
            assertThat(savedOrg).as("Organization phai duoc luu vao DB").isNotNull();
            assertThat(savedOrg.getStatus())
                    .as("Status mac dinh phai la ACTIVE")
                    .isEqualTo(OrganizationStatus.ACTIVE);

            // THEN - 1 Certificate duoc luu kem
            long certCount = certificateRepository.findAll().stream()
                    .filter(c -> savedOrg.getId().equals(c.getOrganizationId()))
                    .count();
            assertThat(certCount)
                    .as("Phai co dung 1 certificate duoc luu")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("Tao trung code -> HTTP 409 Conflict, DB van chi co 1 org")
        void registerOrganization_duplicateCode_shouldReturn409() {
            // GIVEN: da tao lan 1
            restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // WHEN: tao lan 2 voi cung code
            ResponseEntity<Object> response = restTemplate.postForEntity(
                    baseUrl() + "/registry/organizations",
                    buildValidRequest(TEST_ORG_CODE), Object.class);

            // THEN - HTTP 409
            assertThat(response.getStatusCode())
                    .as("Phai tra ve HTTP 409 Conflict khi trung ma co quan")
                    .isEqualTo(HttpStatus.CONFLICT);

            // THEN - DB chi co dung 1 org
            long orgCount = organizationRepository.findAll().stream()
                    .filter(o -> TEST_ORG_CODE.equals(o.getCode()))
                    .count();
            assertThat(orgCount)
                    .as("DB phai chi co dung 1 organization voi code nay")
                    .isEqualTo(1L);
        }
    }
}

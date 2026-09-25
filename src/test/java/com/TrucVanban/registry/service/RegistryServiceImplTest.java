package com.TrucVanban.registry.service;

import com.TrucVanban.registry.dto.request.CertificateRequest;
import com.TrucVanban.registry.dto.request.RegisterOrganizationRequest;
import com.TrucVanban.registry.dto.request.SuspendOrganizationRequest;
import com.TrucVanban.registry.dto.response.RegisterOrganizationResponse;
import com.TrucVanban.registry.dto.response.SuspendOrganizationResponse;
import com.TrucVanban.registry.entity.Certificate;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.CertificateStatus;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.mapper.OrganizationMapper;
import com.TrucVanban.registry.mapper.SlaConfigMapper;
import com.TrucVanban.registry.repository.CertificateRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.registry.repository.OrganizationVisualAssetRepository;
import com.TrucVanban.registry.repository.SlaConfigurationRepository;
import com.TrucVanban.registry.service.impl.RegistryServiceImpl;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.infrastructure.security.hmac.ApiKeyCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test cho RegistryServiceImpl.
 *
 * Sử dụng @Nested để phân nhóm các kịch bản test theo từng hàm nghiệp vụ:
 * - registerOrganization     : kiểm tra duplicate code, lưu org và cert
 * - suspendOrganization      : khóa khẩn cấp + evict API key cache
 * - updateCertificate        : expire cert cũ, tạo cert mới
 * - checkCertificate         : so sánh key + kiểm tra ngày hết hạn
 * - getOrganizationIdsByCode : phát hiện missing codes, bảo toàn thứ tự ID
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegistryServiceImpl Unit Tests")
class RegistryServiceImplTest {

    // ---- Mock toàn bộ dependencies ----
    @Mock private OrganizationRepository organizationRepository;
    @Mock private CertificateRepository certificateRepository;
    @Mock private SlaConfigurationRepository slaConfigurationRepository;
    @Mock private OrganizationVisualAssetRepository visualAssetRepository;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private SlaConfigMapper slaConfigMapper;
    @Mock private ApiKeyCacheService apiKeyCacheService;

    // ---- Class thực sự cần test, Mockito tự inject các @Mock ở trên vào đây ----
    @InjectMocks
    private RegistryServiceImpl registryService;

    // =================================================================
    // 1. registerOrganization
    // =================================================================
    @Nested
    @DisplayName("registerOrganization() - Đăng ký cơ quan")
    class RegisterOrganizationTests {

        @Test
        @DisplayName("Thành công: Lưu org và cert, trả về response")
        void registerOrganization_Success() {
            // ARRANGE
            RegisterOrganizationRequest request = new RegisterOrganizationRequest();
            request.setCode("AGENCY-A");
            request.setName("Cơ quan A");
            request.setReceiveEndpoint("http://agency-a.local/api/receive");
            request.setCertificate(new CertificateRequest());

            Organization savedOrg = Organization.builder().id(1L).code("AGENCY-A").build();
            Certificate savedCert = Certificate.builder().id(10L).build();
            RegisterOrganizationResponse expectedResponse = RegisterOrganizationResponse.builder()
                    .organizationId(1L).code("AGENCY-A").status(OrganizationStatus.ACTIVE)
                    .build();

            when(organizationRepository.existsByCode("AGENCY-A")).thenReturn(false);
            when(organizationMapper.toEntity(request)).thenReturn(savedOrg);
            when(organizationRepository.save(savedOrg)).thenReturn(savedOrg);
            when(organizationMapper.toCertificateEntity(request.getCertificate())).thenReturn(savedCert);
            when(certificateRepository.save(any(Certificate.class))).thenReturn(savedCert);
            when(organizationMapper.toRegisterResponse(savedOrg)).thenReturn(expectedResponse);

            // ACT
            RegisterOrganizationResponse actual = registryService.registerOrganization(request);

            // ASSERT
            assertThat(actual.getCode()).isEqualTo("AGENCY-A");
            assertThat(actual.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);

            // Xác nhận cả 2 lần save: 1 lần cho org, 1 lần cho cert
            verify(organizationRepository, times(1)).save(any(Organization.class));
            verify(certificateRepository, times(1)).save(any(Certificate.class));
        }

        @Test
        @DisplayName("Thất bại: Mã tổ chức đã tồn tại → DuplicateResourceException")
        void registerOrganization_DuplicateCode_ShouldThrow() {
            // ARRANGE
            RegisterOrganizationRequest request = new RegisterOrganizationRequest();
            request.setCode("AGENCY-A");

            when(organizationRepository.existsByCode("AGENCY-A")).thenReturn(true);

            // ACT & ASSERT
            assertThatThrownBy(() -> registryService.registerOrganization(request))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("AGENCY-A");

            // Đảm bảo không có gì được lưu vào DB khi code đã tồn tại
            verify(organizationRepository, never()).save(any());
            verify(certificateRepository, never()).save(any());
        }
    }

    // =================================================================
    // 2. suspendOrganization
    // =================================================================
    @Nested
    @DisplayName("suspendOrganization() - Khóa khẩn cấp tổ chức")
    class SuspendOrganizationTests {

        @Test
        @DisplayName("Thành công: ACTIVE → SUSPENDED, evict API key cache")
        void suspendOrganization_FromActive_ShouldSuspendAndEvictCache() {
            // ARRANGE
            Organization org = Organization.builder()
                    .id(5L).code("AGENCY-B").status(OrganizationStatus.ACTIVE)
                    .build();

            SuspendOrganizationRequest request = new SuspendOrganizationRequest();
            request.setReason("Phát hiện lưu lượng bất thường, nghi ngờ lộ lọt Private Key");

            when(organizationRepository.findByCode("AGENCY-B")).thenReturn(Optional.of(org));
            when(organizationRepository.save(org)).thenReturn(org);

            // ACT
            SuspendOrganizationResponse response = registryService.suspendOrganization("AGENCY-B", request);

            // ASSERT
            assertThat(response.getCode()).isEqualTo("AGENCY-B");
            assertThat(response.getStatus()).isEqualTo(OrganizationStatus.SUSPENDED);

            // Cache phải bị evict ngay để chặn mọi giao dịch
            verify(apiKeyCacheService, times(1)).evictAgencyCache(5L);
            verify(organizationRepository, times(1)).save(org);
        }

        @Test
        @DisplayName("Thất bại: Không tìm thấy tổ chức → ResourceNotFoundException")
        void suspendOrganization_OrgNotFound_ShouldThrow() {
            // ARRANGE
            when(organizationRepository.findByCode("GHOST")).thenReturn(Optional.empty());

            SuspendOrganizationRequest request = new SuspendOrganizationRequest();
            request.setReason("Lý do bất kỳ");

            // ACT & ASSERT
            assertThatThrownBy(() -> registryService.suspendOrganization("GHOST", request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("GHOST");

            verify(apiKeyCacheService, never()).evictAgencyCache(any());
        }

        @Test
        @DisplayName("Thất bại: Tổ chức đã SUSPENDED → BusinessLogicException")
        void suspendOrganization_AlreadySuspended_ShouldThrow() {
            // ARRANGE
            Organization org = Organization.builder()
                    .id(6L).code("AGENCY-C").status(OrganizationStatus.SUSPENDED)
                    .build();

            when(organizationRepository.findByCode("AGENCY-C")).thenReturn(Optional.of(org));

            SuspendOrganizationRequest request = new SuspendOrganizationRequest();
            request.setReason("Lý do bất kỳ");

            // ACT & ASSERT
            assertThatThrownBy(() -> registryService.suspendOrganization("AGENCY-C", request))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("ACTIVE");

            // Không được ghi DB hay evict cache khi validation thất bại
            verify(organizationRepository, never()).save(any());
            verify(apiKeyCacheService, never()).evictAgencyCache(any());
        }
    }

    // =================================================================
    // 3. updateCertificate
    // =================================================================
    @Nested
    @DisplayName("updateCertificate() - Cập nhật chứng thư số")
    class UpdateCertificateTests {

        @Test
        @DisplayName("Cert cũ ACTIVE phải bị EXPIRED trước khi tạo cert mới")
        void updateCertificate_ShouldExpireOldCertAndCreateNew() {
            // ARRANGE
            Organization org = Organization.builder().id(1L).code("AGENCY-A").build();
            Certificate oldActiveCert = Certificate.builder()
                    .id(10L).organizationId(1L).status(CertificateStatus.ACTIVE)
                    .publicKey("old-public-key")
                    .build();

            CertificateRequest newCertRequest = new CertificateRequest();
            Certificate newCertEntity = Certificate.builder().id(20L).build();
            Certificate savedNewCert = Certificate.builder()
                    .id(20L).status(CertificateStatus.ACTIVE)
                    .build();

            when(organizationRepository.findByCode("AGENCY-A")).thenReturn(Optional.of(org));
            when(certificateRepository.findByOrganizationIdAndStatus(1L, CertificateStatus.ACTIVE))
                    .thenReturn(Optional.of(oldActiveCert));
            when(organizationMapper.toCertificateEntity(newCertRequest)).thenReturn(newCertEntity);
            when(certificateRepository.save(any(Certificate.class))).thenReturn(savedNewCert);

            // ACT
            registryService.updateCertificate("AGENCY-A", newCertRequest);

            // ASSERT
            ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
            verify(certificateRepository, times(2)).save(captor.capture());

            List<Certificate> savedCerts = captor.getAllValues();

            // Lần save đầu tiên: cert cũ phải được đặt thành EXPIRED
            assertThat(savedCerts.get(0).getId()).isEqualTo(10L);
            assertThat(savedCerts.get(0).getStatus()).isEqualTo(CertificateStatus.EXPIRED);

            // Lần save thứ hai: cert mới phải có status ACTIVE
            assertThat(savedCerts.get(1).getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        }
    }

    // =================================================================
    // 4. checkCertificate
    // =================================================================
    @Nested
    @DisplayName("checkCertificate() - Kiểm tra tính hợp lệ chứng thư số")
    class CheckCertificateTests {

        @Test
        @DisplayName("Trả về true khi cert còn hạn và public key khớp")
        void checkCertificate_ValidCert_ShouldReturnTrue() {
            // ARRANGE
            Certificate cert = Certificate.builder()
                    .organizationId(1L)
                    .publicKey("valid-signature")
                    .expiredAt(LocalDateTime.now().plusDays(30))
                    .build();

            when(certificateRepository.findByOrganizationIdAndStatus(1L, CertificateStatus.ACTIVE))
                    .thenReturn(Optional.of(cert));

            // ACT
            boolean result = registryService.checkCertificate("valid-signature", 1L);

            // ASSERT
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Trả về false khi cert đã hết hạn (dù public key khớp)")
        void checkCertificate_ExpiredCert_ShouldReturnFalse() {
            // ARRANGE
            Certificate cert = Certificate.builder()
                    .organizationId(1L)
                    .publicKey("valid-signature")
                    .expiredAt(LocalDateTime.now().minusDays(1))
                    .build();

            when(certificateRepository.findByOrganizationIdAndStatus(1L, CertificateStatus.ACTIVE))
                    .thenReturn(Optional.of(cert));

            // ACT
            boolean result = registryService.checkCertificate("valid-signature", 1L);

            // ASSERT
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Trả về false khi public key không khớp")
        void checkCertificate_WrongPublicKey_ShouldReturnFalse() {
            // ARRANGE
            Certificate cert = Certificate.builder()
                    .organizationId(1L)
                    .publicKey("correct-key")
                    .expiredAt(LocalDateTime.now().plusDays(30))
                    .build();

            when(certificateRepository.findByOrganizationIdAndStatus(1L, CertificateStatus.ACTIVE))
                    .thenReturn(Optional.of(cert));

            // ACT
            boolean result = registryService.checkCertificate("wrong-key", 1L);

            // ASSERT
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Trả về false khi không có cert ACTIVE nào")
        void checkCertificate_NoCertFound_ShouldReturnFalse() {
            // ARRANGE
            when(certificateRepository.findByOrganizationIdAndStatus(1L, CertificateStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            // ACT
            boolean result = registryService.checkCertificate("any-signature", 1L);

            // ASSERT
            assertThat(result).isFalse();
        }
    }

    // =================================================================
    // 5. getOrganizationIdsByCode
    // =================================================================
    @Nested
    @DisplayName("getOrganizationIdsByCode() - Tra cứu ID theo mã cơ quan")
    class GetOrganizationIdsByCodeTests {

        @Test
        @DisplayName("Tất cả code tồn tại: Trả về đúng thứ tự ID")
        void getOrganizationIdsByCode_AllFound_ShouldReturnOrderedIds() {
            // ARRANGE
            Organization orgA = Organization.builder().id(1L).code("AGENCY-A").build();
            Organization orgB = Organization.builder().id(2L).code("AGENCY-B").build();

            when(organizationRepository.findByCodeIn(List.of("AGENCY-A", "AGENCY-B")))
                    .thenReturn(List.of(orgB, orgA));

            // ACT
            List<Long> ids = registryService.getOrganizationIdsByCode(List.of("AGENCY-A", "AGENCY-B"));

            // ASSERT
            assertThat(ids).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("Có code không tồn tại trong DB → ResourceNotFoundException")
        void getOrganizationIdsByCode_SomeMissing_ShouldThrow() {
            // ARRANGE
            Organization orgA = Organization.builder().id(1L).code("AGENCY-A").build();

            when(organizationRepository.findByCodeIn(List.of("AGENCY-A", "AGENCY-GHOST")))
                    .thenReturn(List.of(orgA));

            // ACT & ASSERT
            assertThatThrownBy(() -> registryService.getOrganizationIdsByCode(
                    List.of("AGENCY-A", "AGENCY-GHOST")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("AGENCY-GHOST");
        }

        @Test
        @DisplayName("Danh sách rỗng: Trả về list rỗng, không gọi DB")
        void getOrganizationIdsByCode_EmptyInput_ShouldReturnEmpty() {
            // ACT
            List<Long> ids = registryService.getOrganizationIdsByCode(List.of());

            // ASSERT
            assertThat(ids).isEmpty();
            verify(organizationRepository, never()).findByCodeIn(any());
        }
    }
}

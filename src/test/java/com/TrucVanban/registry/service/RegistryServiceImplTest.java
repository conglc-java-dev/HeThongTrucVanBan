package com.TrucVanban.registry.service;

import com.TrucVanban.registry.dto.request.CertificateRequest;
import com.TrucVanban.registry.dto.request.RegisterOrganizationRequest;
import com.TrucVanban.registry.dto.request.UpdateOrganizationStatusRequest;
import com.TrucVanban.registry.dto.response.RegisterOrganizationResponse;
import com.TrucVanban.registry.dto.response.UpdateOrganizationStatusResponse;
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
import com.TrucVanban.registry.validator.OrganizationStateTransitionValidator;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.shared.security.hmac.ApiKeyCacheService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test cho RegistryServiceImpl.
 *
 * Sử dụng @Nested để phân nhóm các kịch bản test theo từng hàm nghiệp vụ:
 * - registerOrganization     : kiểm tra duplicate code, lưu org và cert
 * - updateOrganizationStatus : state machine + cache eviction
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
    @Mock private OrganizationStateTransitionValidator organizationStateTransitionValidator;
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
                    .organizationId(1L).code("AGENCY-A").status(OrganizationStatus.PENDING_APPROVAL)
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
            assertThat(actual.getStatus()).isEqualTo(OrganizationStatus.PENDING_APPROVAL);

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
    // 2. updateOrganizationStatus
    // =================================================================
    @Nested
    @DisplayName("updateOrganizationStatus() - Cập nhật trạng thái cơ quan")
    class UpdateOrganizationStatusTests {

        @Test
        @DisplayName("Thất bại: Không tìm thấy tổ chức → ResourceNotFoundException")
        void updateOrganizationStatus_OrgNotFound_ShouldThrow() {
            // ARRANGE
            when(organizationRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

            UpdateOrganizationStatusRequest request = new UpdateOrganizationStatusRequest();
            request.setStatus(OrganizationStatus.ACTIVE);

            // ACT & ASSERT
            assertThatThrownBy(() -> registryService.updateOrganizationStatus("UNKNOWN", request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("UNKNOWN");
        }

        @Test
        @DisplayName("Chuyển sang SUSPENDED: Phải evict API key cache")
        void updateOrganizationStatus_ToSuspended_ShouldEvictCache() {
            // ARRANGE
            Organization org = Organization.builder()
                    .id(5L).code("AGENCY-B").status(OrganizationStatus.ACTIVE)
                    .build();

            UpdateOrganizationStatusRequest request = new UpdateOrganizationStatusRequest();
            request.setStatus(OrganizationStatus.SUSPENDED);
            request.setReason("Vi phạm quy định bảo mật");

            when(organizationRepository.findByCode("AGENCY-B")).thenReturn(Optional.of(org));
            when(organizationRepository.save(org)).thenReturn(org);

            // ACT
            registryService.updateOrganizationStatus("AGENCY-B", request);

            // ASSERT - Quan trọng: cache phải được xóa khi SUSPENDED
            verify(apiKeyCacheService, times(1)).evictAgencyCache(5L);
            verify(organizationRepository, times(1)).save(org);
        }

        @Test
        @DisplayName("Chuyển sang REJECTED: Lưu rejectReason + evict cache")
        void updateOrganizationStatus_ToRejected_ShouldSaveReasonAndEvictCache() {
            // ARRANGE
            Organization org = Organization.builder()
                    .id(6L).code("AGENCY-C").status(OrganizationStatus.PENDING_APPROVAL)
                    .build();

            UpdateOrganizationStatusRequest request = new UpdateOrganizationStatusRequest();
            request.setStatus(OrganizationStatus.REJECTED);
            request.setReason("Hồ sơ không hợp lệ");

            when(organizationRepository.findByCode("AGENCY-C")).thenReturn(Optional.of(org));
            when(organizationRepository.save(org)).thenReturn(org);

            // ACT
            registryService.updateOrganizationStatus("AGENCY-C", request);

            // ASSERT
            ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
            verify(organizationRepository).save(captor.capture());

            Organization savedOrg = captor.getValue();
            assertThat(savedOrg.getStatus()).isEqualTo(OrganizationStatus.REJECTED);
            assertThat(savedOrg.getRejectReason()).isEqualTo("Hồ sơ không hợp lệ");

            // Cache cũng phải được evict
            verify(apiKeyCacheService, times(1)).evictAgencyCache(6L);
        }

        @Test
        @DisplayName("Chuyển sang ACTIVE: Xóa rejectReason, KHÔNG evict cache")
        void updateOrganizationStatus_ToActive_ShouldClearRejectReasonAndNotEvictCache() {
            // ARRANGE
            Organization org = Organization.builder()
                    .id(7L).code("AGENCY-D")
                    .status(OrganizationStatus.SUSPENDED)
                    .rejectReason("Lý do cũ")
                    .build();

            UpdateOrganizationStatusRequest request = new UpdateOrganizationStatusRequest();
            request.setStatus(OrganizationStatus.ACTIVE);

            when(organizationRepository.findByCode("AGENCY-D")).thenReturn(Optional.of(org));
            when(organizationRepository.save(org)).thenReturn(org);

            // ACT
            registryService.updateOrganizationStatus("AGENCY-D", request);

            // ASSERT
            ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
            verify(organizationRepository).save(captor.capture());

            Organization savedOrg = captor.getValue();
            assertThat(savedOrg.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
            assertThat(savedOrg.getRejectReason()).isNull();

            // ACTIVE không evict cache
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

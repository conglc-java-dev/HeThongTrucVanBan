package com.TrucVanban.registry.service;

import com.TrucVanban.registry.dto.response.CreateApiKeyResponse;
import com.TrucVanban.registry.entity.ApiKey;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.ApiKeyStatus;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.ApiKeyRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.registry.service.impl.ApiKeyManagementServiceImpl;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import com.TrucVanban.shared.security.hmac.AesGcmEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test cho ApiKeyManagementServiceImpl.
 *
 * Tập trung vào các guard clause quan trọng:
 * - createApiKey : chỉ cấp key khi org tồn tại VÀ đang ACTIVE
 * - revokeApiKey : thu hồi key ACTIVE + phải xóa cache Redis
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceImplTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AesGcmEncryptionService aesGcmEncryptionService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ApiKeyManagementServiceImpl apiKeyManagementService;

    // =================================================================
    // createApiKey
    // =================================================================

    @Test
    @DisplayName("createApiKey - Thất bại: Tổ chức không tồn tại → ResourceNotFoundException")
    void createApiKey_OrgNotFound_ShouldThrow() {
        // ARRANGE
        when(organizationRepository.findByCode("GHOST-ORG")).thenReturn(Optional.empty());

        // ACT & ASSERT
        assertThatThrownBy(() -> apiKeyManagementService.createApiKey("GHOST-ORG", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("GHOST-ORG");

        // Không được lưu bất kỳ key nào vào DB
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("createApiKey - Thất bại: Tổ chức không ở trạng thái ACTIVE (SUSPENDED) → BusinessLogicException")
    void createApiKey_OrgNotActive_ShouldThrow() {
        // ARRANGE
        Organization suspendedOrg = Organization.builder()
                .id(1L).code("AGENCY-A")
                .status(OrganizationStatus.SUSPENDED) // không phải ACTIVE
                .build();

        when(organizationRepository.findByCode("AGENCY-A")).thenReturn(Optional.of(suspendedOrg));

        // ACT & ASSERT
        assertThatThrownBy(() -> apiKeyManagementService.createApiKey("AGENCY-A", null))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("ACTIVE"); // message phải giải thích vì sao bị từ chối

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("createApiKey - Thất bại: Tổ chức PENDING_APPROVAL → BusinessLogicException")
    void createApiKey_OrgPendingApproval_ShouldThrow() {
        // ARRANGE
        Organization pendingOrg = Organization.builder()
                .id(2L).code("AGENCY-B")
                .status(OrganizationStatus.PENDING_APPROVAL)
                .build();

        when(organizationRepository.findByCode("AGENCY-B")).thenReturn(Optional.of(pendingOrg));

        // ACT & ASSERT
        assertThatThrownBy(() -> apiKeyManagementService.createApiKey("AGENCY-B", null))
                .isInstanceOf(BusinessLogicException.class);

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("createApiKey - Thành công: Tổ chức ACTIVE → Tạo key có prefix 'tvb_live_'")
    void createApiKey_ActiveOrg_ShouldCreateKeyWithCorrectPrefix() {
        // ARRANGE
        Organization activeOrg = Organization.builder()
                .id(10L).code("AGENCY-C")
                .status(OrganizationStatus.ACTIVE)
                .build();

        ApiKey savedApiKey = ApiKey.builder()
                .id(1L).agencyId(10L).keyId("tvb_live_abc123")
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        when(organizationRepository.findByCode("AGENCY-C")).thenReturn(Optional.of(activeOrg));
        when(aesGcmEncryptionService.encrypt(anyString())).thenReturn("encrypted-secret");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(savedApiKey);

        // ACT
        CreateApiKeyResponse response = apiKeyManagementService.createApiKey("AGENCY-C", null);

        // ASSERT
        assertThat(response).isNotNull();
        assertThat(response.getKeyId()).startsWith("tvb_live_"); // format keyId phải đúng
        assertThat(response.getAgencyId()).isEqualTo(10L);
        assertThat(response.getAgencyCode()).isEqualTo("AGENCY-C");
        assertThat(response.getSecret()).isNotBlank(); // secret phải được trả về (chỉ lần đầu này)

        // Verify save đã được gọi đúng 1 lần
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository, times(1)).save(captor.capture());

        ApiKey savedKey = captor.getValue();
        assertThat(savedKey.getAgencyId()).isEqualTo(10L);
        assertThat(savedKey.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(savedKey.getAlgorithm()).isEqualTo("HMAC_SHA256");
    }

    // =================================================================
    // revokeApiKey
    // =================================================================

    @Test
    @DisplayName("revokeApiKey - Thất bại: Key không tồn tại hoặc không ACTIVE → ResourceNotFoundException")
    void revokeApiKey_KeyNotFound_ShouldThrow() {
        // ARRANGE
        when(apiKeyRepository.findByKeyIdAndStatus("invalid-key-id", ApiKeyStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // ACT & ASSERT
        assertThatThrownBy(() -> apiKeyManagementService.revokeApiKey("invalid-key-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("invalid-key-id");
    }

    @Test
    @DisplayName("revokeApiKey - Thành công: Key bị set REVOKED + 2 Redis key bị xóa")
    void revokeApiKey_ActiveKey_ShouldRevokeAndEvictRedisCache() {
        // ARRANGE
        ApiKey activeKey = ApiKey.builder()
                .id(1L).keyId("tvb_live_abc123")
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        when(apiKeyRepository.findByKeyIdAndStatus("tvb_live_abc123", ApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(activeKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(activeKey);

        // ACT
        apiKeyManagementService.revokeApiKey("tvb_live_abc123");

        // ASSERT

        // 1. Key phải được lưu với trạng thái REVOKED
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository, times(1)).save(captor.capture());
        ApiKey revokedKey = captor.getValue();
        assertThat(revokedKey.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revokedKey.getRevokedAt()).isNotNull(); // revokedAt phải được gán

        // 2. Cả 2 Redis key phải được xóa (apikey:xxx và apikey:miss:xxx)
        verify(redisTemplate, times(1)).delete("apikey:tvb_live_abc123");
        verify(redisTemplate, times(1)).delete("apikey:miss:tvb_live_abc123");
    }
}

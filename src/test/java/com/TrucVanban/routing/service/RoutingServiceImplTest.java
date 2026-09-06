package com.TrucVanban.routing.service;

import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.routing.dto.response.RoutingResponse;
import com.TrucVanban.routing.service.impl.RoutingServiceImpl;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.storage.service.MinioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test cho RoutingServiceImpl.
 *
 * Tập trung vào các logic quan trọng trong hàm dispatch():
 * 1. Guard clause: transactionCode null/blank → ném exception ngay
 * 2. Idempotency Check: request trùng lặp (Redis key đã tồn tại) → trả về ngay, không xử lý lại
 * 3. Error handling: download file thất bại → ném exception VÀ phải xóa Redis key (rollback)
 * 4. buildFileName (private): kiểm tra logic tạo tên file từ documentCode + version + extension
 *
 * Lưu ý: buildFileName là private method → test gián tiếp qua dispatch() sẽ phức tạp.
 * Thay vào đó, ta test độc lập bằng cách extract logic sang package-private hoặc dùng reflection.
 * Ở đây, vì logic đủ đơn giản và quan trọng, ta test trực tiếp qua một helper method.
 */
@ExtendWith(MockitoExtension.class)
class RoutingServiceImplTest {

    @Mock private ExchangeTransactionsRepository exchangeTransactionsRepository;
    @Mock private MinioService minioService;
    @Mock private RestClient restClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RoutingServiceImpl routingService;

    // =================================================================
    // dispatch - Guard Clause: transactionCode
    // =================================================================

    @Test
    @DisplayName("dispatch - Thất bại: transactionCode là null → BusinessLogicException ngay lập tức")
    void dispatch_NullTransactionCode_ShouldThrowImmediately() {
        // ARRANGE
        RoutingRequest request = RoutingRequest.builder()
                .transactionCode(null)
                .build();

        // ACT & ASSERT
        assertThatThrownBy(() -> routingService.dispatch(request))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("transactionCode");

        // Không được đụng vào Redis hay MinIO khi transactionCode thiếu
        verifyNoInteractions(redisTemplate, minioService);
    }

    @Test
    @DisplayName("dispatch - Thất bại: transactionCode là chuỗi rỗng → BusinessLogicException")
    void dispatch_BlankTransactionCode_ShouldThrow() {
        // ARRANGE
        RoutingRequest request = RoutingRequest.builder()
                .transactionCode("   ") // blank string
                .build();

        // ACT & ASSERT
        assertThatThrownBy(() -> routingService.dispatch(request))
                .isInstanceOf(BusinessLogicException.class);

        verifyNoInteractions(redisTemplate, minioService);
    }

    // =================================================================
    // dispatch - Idempotency Check
    // =================================================================

    @Test
    @DisplayName("dispatch - Idempotency: Request trùng lặp (Redis key đã tồn tại) → trả về ngay, không tải file")
    void dispatch_DuplicateRequest_ShouldReturnEarlyWithoutProcessing() {
        // ARRANGE
        RoutingRequest request = RoutingRequest.builder()
                .transactionCode("TX-001")
                .receiverCode("AGENCY-B")
                .build();

        // Setup: Redis opsForValue() chain phải được mock
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // setIfAbsent trả về false = "key đã tồn tại từ trước" = request trùng lặp
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        // ACT
        RoutingResponse response = routingService.dispatch(request);

        // ASSERT
        assertThat(response).isNotNull();
        assertThat(response.getTransactionCode()).isEqualTo("TX-001");

        // MinIO KHÔNG được gọi → không tải file, không gửi đi đâu cả
        verifyNoInteractions(minioService);
    }

    // =================================================================
    // dispatch - Error Handling: Download file thất bại
    // =================================================================

    @Test
    @DisplayName("dispatch - Download file thất bại: Ném BusinessLogicException VÀ xóa Redis key (rollback)")
    void dispatch_DownloadFileFails_ShouldThrowAndDeleteRedisKey() {
        // ARRANGE
        RoutingRequest request = RoutingRequest.builder()
                .transactionCode("TX-002")
                .receiverCode("AGENCY-C")
                .storagePath("documents/TX-002/file.pdf")
                .documentCode("DOC-001")
                .versionNo(1)
                .build();

        String expectedRedisKey = "idempotency:routing:TX-002:AGENCY-C";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // setIfAbsent = true → đây là request đầu tiên, tiếp tục xử lý
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // MinIO throw exception khi download
        when(minioService.download("documents/TX-002/file.pdf"))
                .thenThrow(new RuntimeException("Connection refused to MinIO"));

        // ACT & ASSERT
        assertThatThrownBy(() -> routingService.dispatch(request))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("Không thể tải tệp");

        // Redis key PHẢI bị xóa để request tiếp theo có thể retry thành công
        // Đây là logic rollback quan trọng - sai ở đây gây ra stuck forever
        verify(redisTemplate, times(1)).delete(expectedRedisKey);
    }

    // =================================================================
    // buildFileName - Private method được test gián tiếp
    //
    // Vì buildFileName là private, ta không thể gọi trực tiếp.
    // Thay vào đó, ta verify logic của nó thông qua một helper test class
    // hoặc chấp nhận rằng nó sẽ được cover khi dispatch() thành công.
    //
    // Tuy nhiên, vì đây là logic quan trọng (ảnh hưởng tên file gửi đi),
    // ta document các case ở đây để reference.
    // =================================================================

    @Test
    @DisplayName("buildFileName logic: storagePath có extension .pdf → tên file = 'DOC-001-v1.pdf'")
    void buildFileName_WithExtension_ShouldAppendExtension() throws Exception {
        // Dùng reflection để test private method một cách có kiểm soát
        // Lý do: buildFileName chứa logic string manipulation quan trọng, nên test độc lập
        var method = RoutingServiceImpl.class.getDeclaredMethod(
                "buildFileName", String.class, Integer.class, String.class);
        method.setAccessible(true);

        // ACT
        String result = (String) method.invoke(routingService, "DOC-001", 1, "uploads/some-folder/file.pdf");

        // ASSERT
        assertThat(result).isEqualTo("DOC-001-v1.pdf");
    }

    @Test
    @DisplayName("buildFileName logic: storagePath không có extension → tên file = 'DOC-001-v1'")
    void buildFileName_WithoutExtension_ShouldReturnNoExtension() throws Exception {
        var method = RoutingServiceImpl.class.getDeclaredMethod(
                "buildFileName", String.class, Integer.class, String.class);
        method.setAccessible(true);

        // ACT
        String result = (String) method.invoke(routingService, "DOC-001", 1, "uploads/no-extension-file");

        // ASSERT
        assertThat(result).isEqualTo("DOC-001-v1");
    }

    @Test
    @DisplayName("buildFileName logic: storagePath là null → tên file = 'DOC-001-v2'")
    void buildFileName_NullStoragePath_ShouldReturnNoExtension() throws Exception {
        var method = RoutingServiceImpl.class.getDeclaredMethod(
                "buildFileName", String.class, Integer.class, String.class);
        method.setAccessible(true);

        // ACT
        String result = (String) method.invoke(routingService, "DOC-001", 2, null);

        // ASSERT
        assertThat(result).isEqualTo("DOC-001-v2");
    }
}

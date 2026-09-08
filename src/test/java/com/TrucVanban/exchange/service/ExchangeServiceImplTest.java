package com.TrucVanban.exchange.service;

import com.TrucVanban.exchange.dto.request.RevokeDocumentRequest;
import com.TrucVanban.exchange.dto.request.UpdateDocumentRequest;
import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.DocumentReceiver;
import com.TrucVanban.exchange.entity.DocumentVersion;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.entity.StatusHistory;
import com.TrucVanban.exchange.enums.DocumentStatus;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.mapper.DocumentMapper;
import com.TrucVanban.exchange.repository.*;
import com.TrucVanban.exchange.service.impl.ExchangeServiceImpl;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.exception.*;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import com.TrucVanban.shared.validator.MultiSignatureValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test cho ExchangeServiceImpl.
 *
 * Sử dụng @Nested để phân nhóm các kịch bản test theo từng hàm nghiệp vụ:
 *
 * 1. exchangeDocument:
 *    - Idempotency-Key null → InvalidInputException
 *    - Redis key đã tồn tại và COMPLETED → BusinessLogicException (chống replay)
 *    - Redis key đang PROCESSING → DuplicateResourceException
 *    - documentCode đã tồn tại → DuplicateResourceException + Redis rollback
 *    - replacedDocumentCode không thuộc quyền sở hữu → ForbiddenException + Redis rollback
 *    - replacedDoc có trạng thái không hợp lệ → BusinessLogicException + Redis rollback
 *
 * 2. ackDocument:
 *    - Transaction chưa được chuyển tới đơn vị nhận → BusinessLogicException
 *    - Receiver không khớp với transaction → ForbiddenException
 *    - Happy path: lưu receiver, status history và cập nhật transaction thành DELIVERED
 *
 * 3. revokeDocument:
 *    - Document không tồn tại → ResourceNotFoundException
 *    - Requester không phải cơ quan gửi gốc → ForbiddenException
 *    - Document không ở trạng thái ACTIVE → BusinessLogicException
 *    - Happy path: đổi status → RECALLED và tạo transaction REVOKE
 *
 * 4. updateDocument:
 *    - Requester không phải cơ quan gửi gốc → ForbiddenException
 *    - Document không ở trạng thái ACTIVE → BusinessLogicException
 *    - Happy path: tạo version mới và tăng currentVersion
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExchangeServiceImpl Unit Tests")
class ExchangeServiceImplTest {

    // ── Dependencies ──────────────────────────────────────────────────────────
    @Mock private RegistryService registryService;
    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentRepository documentRepository;
    @Mock private ExchangeTransactionsRepository exchangeTransactionsRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentReplacementRepository documentReplacementRepository;
    @Mock private DocumentReceiverRepository documentReceiverRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private MultiSignatureValidator multiSignatureValidator;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ExchangeServiceImpl exchangeService;

    // =================================================================
    // 1. exchangeDocument
    // =================================================================
    @Nested
    @DisplayName("exchangeDocument() - Tiếp nhận và gửi văn bản")
    class ExchangeDocumentTests {

        @Test
        @DisplayName("Thất bại: Idempotency-Key null → InvalidInputException")
        void exchangeDocument_NullIdempotencyKey_ShouldThrow() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, null))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageContaining("Idempotency-Key");

            // Redis và các repository KHÔNG được chạm tới
            verifyNoInteractions(redisTemplate, documentRepository, registryService);
        }

        @Test
        @DisplayName("Thất bại: Redis key đã COMPLETED → BusinessLogicException (chống replay)")
        void exchangeDocument_IdempotencyKeyAlreadyCompleted_ShouldThrow() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();
            String idemKey = "key-001";

            // Redis chain: opsForValue().setIfAbsent() trả về false (key đã tồn tại)
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(false);
            // Key đã có giá trị COMPLETED → request đã xử lý xong
            when(valueOperations.get(anyString())).thenReturn("COMPLETED");

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, idemKey))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("đã được xử lý");

            verifyNoInteractions(documentRepository);
        }

        @Test
        @DisplayName("Thất bại: Redis key đang PROCESSING → DuplicateResourceException")
        void exchangeDocument_IdempotencyKeyProcessing_ShouldThrow() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();
            String idemKey = "key-002";

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(false);
            // Key đang PROCESSING (request khác đang chạy song song)
            when(valueOperations.get(anyString())).thenReturn("PROCESSING");

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, idemKey))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("đang được xử lý");
        }

        @Test
        @DisplayName("Thất bại: documentCode đã tồn tại → DuplicateResourceException + Redis rollback")
        void exchangeDocument_DuplicateDocumentCode_ShouldThrowAndRollbackRedis() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();
            request.setSenderCode("AGENCY-A");
            request.setDocumentCode("DOC-001");
            request.setReceiverCodes(List.of("AGENCY-B"));
            String idemKey = "key-003";

            // Redis: claim thành công (key mới)
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(true);

            // documentCode đã tồn tại trong DB → exception ném TRƯỚC khi resolve sender/receiver
            when(documentRepository.existsDocumentByDocumentCode("DOC-001")).thenReturn(true);

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, idemKey))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("DOC-001");

            // Redis key phải bị XÓA để cho phép retry
            verify(redisTemplate, times(1)).delete(anyString());
        }

        @Test
        @DisplayName("Thất bại: replacedDoc không thuộc cơ quan gửi → ForbiddenException + Redis rollback")
        void exchangeDocument_ReplacedDocumentBelongsToDifferentOrg_ShouldThrowAndRollbackRedis() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();
            request.setSenderCode("AGENCY-A");
            request.setDocumentCode("DOC-NEW");
            request.setReceiverCodes(List.of("AGENCY-B"));
            request.setReplacedDocumentCode("DOC-OLD");
            String idemKey = "key-004";

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(true);

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);
            when(registryService.getOrganizationIdsByCode(anyList())).thenReturn(List.of(20L));
            when(documentRepository.existsDocumentByDocumentCode("DOC-NEW")).thenReturn(false);

            // DOC-OLD thuộc về cơ quan khác (senderOrgId = 99L, không phải 10L)
            Document replacedDoc = Document.builder()
                    .id(1L)
                    .documentCode("DOC-OLD")
                    .senderOrgId(99L)
                    .status(DocumentStatus.ACTIVE)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-OLD")).thenReturn(Optional.of(replacedDoc));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, idemKey))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("quyền thay thế");

            verify(redisTemplate, times(1)).delete(anyString());
        }

        @Test
        @DisplayName("Thất bại: replacedDoc có trạng thái REPLACED → BusinessLogicException + Redis rollback")
        void exchangeDocument_ReplacedDocumentWithInvalidStatus_ShouldThrowAndRollbackRedis() {
            // ARRANGE
            ExchangeDocumentRequest request = new ExchangeDocumentRequest();
            request.setSenderCode("AGENCY-A");
            request.setDocumentCode("DOC-NEW");
            request.setReceiverCodes(List.of("AGENCY-B"));
            request.setReplacedDocumentCode("DOC-OLD");
            String idemKey = "key-005";

            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(true);

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);
            when(registryService.getOrganizationIdsByCode(anyList())).thenReturn(List.of(20L));
            when(documentRepository.existsDocumentByDocumentCode("DOC-NEW")).thenReturn(false);

            // DOC-OLD đã bị thay thế trước đó → không thể thay thế tiếp
            Document replacedDoc = Document.builder()
                    .id(1L)
                    .documentCode("DOC-OLD")
                    .senderOrgId(10L)
                    .status(DocumentStatus.REPLACED)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-OLD")).thenReturn(Optional.of(replacedDoc));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.exchangeDocument(request, idemKey))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("REPLACED");

            verify(redisTemplate, times(1)).delete(anyString());
        }
    }

    // =================================================================
    // 2. ackDocument
    // =================================================================
    @Nested
    @DisplayName("ackDocument() - Ghi nhận nhận văn bản")
    class AckDocumentTests {

        @Test
        @DisplayName("Thất bại: Transaction chưa được chuyển tới đơn vị nhận → BusinessLogicException")
        void ackDocument_TransactionNotDelivered_ShouldThrow() {
            // ARRANGE
            ReceiveDocumentRequest request = new ReceiveDocumentRequest();
            request.setTransactionCode("TXN-001");
            request.setReceiverCode("AGENCY-B");

            ExchangeTransactions transaction = ExchangeTransactions.builder()
                    .transactionCode("TXN-001")
                    .currentStatus(TransactionStatus.RECEIVED)
                    .build();
            when(exchangeTransactionsRepository.findByTransactionCode("TXN-001"))
                    .thenReturn(Optional.of(transaction));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.ackDocument(request))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("Trạng thái hiện tại: RECEIVED");

            verifyNoInteractions(registryService);
            verify(exchangeTransactionsRepository, never()).save(any());
        }

        @Test
        @DisplayName("Thất bại: Receiver không khớp với transaction → ForbiddenException")
        void ackDocument_WrongReceiver_ShouldThrow() {
            // ARRANGE
            ReceiveDocumentRequest request = new ReceiveDocumentRequest();
            request.setTransactionCode("TXN-001");
            request.setReceiverCode("AGENCY-C"); // giả mạo receiver

            when(registryService.getOrganizationIdByCode("AGENCY-C")).thenReturn(30L);

            // Transaction thực sự thuộc về AGENCY-B (receiverOrgId = 20L)
            ExchangeTransactions transaction = ExchangeTransactions.builder()
                    .id(1L)
                    .transactionCode("TXN-001")
                    .receiverOrgId(20L)
                    .currentStatus(TransactionStatus.DELIVERED)
                    .build();
            when(exchangeTransactionsRepository.findByTransactionCode("TXN-001"))
                    .thenReturn(Optional.of(transaction));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.ackDocument(request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("quyền ghi nhận");

            verify(exchangeTransactionsRepository).findByTransactionCode("TXN-001");
            verify(exchangeTransactionsRepository, never()).save(any());
        }

        @Test
        @DisplayName("Thành công: Lưu đúng DocumentReceiver và StatusHistory với ID chính xác")
        void ackDocument_ValidRequest_ShouldSaveReceiverAndStatusHistory() {
            // ARRANGE
            ReceiveDocumentRequest request = new ReceiveDocumentRequest();
            request.setTransactionCode("TXN-001");
            request.setReceiverCode("AGENCY-B");

            when(registryService.getOrganizationIdByCode("AGENCY-B")).thenReturn(20L);

            ExchangeTransactions transaction = ExchangeTransactions.builder()
                    .id(5L)
                    .transactionCode("TXN-001")
                    .documentId(100L)
                    .receiverOrgId(20L)
                    .currentStatus(TransactionStatus.DISPATCHED)
                    .build();
            when(exchangeTransactionsRepository.findByTransactionCode("TXN-001"))
                    .thenReturn(Optional.of(transaction));

            // Mock mapper trả về object giả (chưa có ID)
            DocumentReceiver receiver = new DocumentReceiver();
            StatusHistory history = new StatusHistory();
            when(documentMapper.toDocumentReceiver(request)).thenReturn(receiver);
            when(documentMapper.toStatusHistory(request)).thenReturn(history);

            // ACT
            var response = exchangeService.ackDocument(request);

            // ASSERT — response đúng
            assertThat(response).isNotNull();
            assertThat(response.getTransactionCode()).isEqualTo("TXN-001");
            verify(exchangeTransactionsRepository).findByTransactionCode("TXN-001");

            // Service phải set documentId và receiverOrgId trước khi save
            ArgumentCaptor<DocumentReceiver> receiverCaptor = ArgumentCaptor.forClass(DocumentReceiver.class);
            verify(documentReceiverRepository).save(receiverCaptor.capture());
            assertThat(receiverCaptor.getValue().getDocumentId()).isEqualTo(100L);
            assertThat(receiverCaptor.getValue().getReceiverOrgId()).isEqualTo(20L);

            // Service phải set transactionId và actorOrgId trước khi save
            ArgumentCaptor<StatusHistory> historyCaptor = ArgumentCaptor.forClass(StatusHistory.class);
            verify(statusHistoryRepository).save(historyCaptor.capture());
            assertThat(historyCaptor.getValue().getTransactionId()).isEqualTo(5L);
            assertThat(historyCaptor.getValue().getActorOrgId()).isEqualTo(20L);

            ArgumentCaptor<ExchangeTransactions> transactionCaptor =
                    ArgumentCaptor.forClass(ExchangeTransactions.class);
            verify(exchangeTransactionsRepository).save(transactionCaptor.capture());
            assertThat(transactionCaptor.getValue().getCurrentStatus()).isEqualTo(TransactionStatus.DELIVERED);
        }
    }

    // =================================================================
    // 3. revokeDocument
    // =================================================================
    @Nested
    @DisplayName("revokeDocument() - Thu hồi văn bản")
    class RevokeDocumentTests {

        @Test
        @DisplayName("Thất bại: Không tìm thấy văn bản → ResourceNotFoundException")
        void revokeDocument_DocumentNotFound_ShouldThrow() {
            // ARRANGE
            RevokeDocumentRequest request = new RevokeDocumentRequest();
            request.setRequesterCode("AGENCY-A");
            request.setReason("Lý do thu hồi");

            when(documentRepository.findByDocumentCode("DOC-999")).thenReturn(Optional.empty());

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.revokeDocument("DOC-999", request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("DOC-999");
        }

        @Test
        @DisplayName("Thất bại: Requester không phải cơ quan gửi gốc → ForbiddenException")
        void revokeDocument_RequesterIsNotOriginalSender_ShouldThrow() {
            // ARRANGE
            RevokeDocumentRequest request = new RevokeDocumentRequest();
            request.setRequesterCode("AGENCY-B");
            request.setReason("Lý do thu hồi");

            when(registryService.getOrganizationIdByCode("AGENCY-B")).thenReturn(20L);

            // Văn bản gốc do AGENCY-A (ID = 10L) gửi
            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L)
                    .status(DocumentStatus.ACTIVE)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.revokeDocument("DOC-001", request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("cơ quan gửi gốc");
        }

        @Test
        @DisplayName("Thất bại: Document không ở trạng thái ACTIVE → BusinessLogicException")
        void revokeDocument_DocumentNotActive_ShouldThrow() {
            // ARRANGE
            RevokeDocumentRequest request = new RevokeDocumentRequest();
            request.setRequesterCode("AGENCY-A");
            request.setReason("Lý do thu hồi");

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);

            // Văn bản đã bị thu hồi rồi
            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L)
                    .status(DocumentStatus.RECALLED)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.revokeDocument("DOC-001", request))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("RECALLED");
        }

        @Test
        @DisplayName("Thành công: Document đổi sang RECALLED và tạo ExchangeTransactions REVOKE")
        void revokeDocument_ValidRequest_ShouldRecallDocumentAndCreateTransaction() {
            // ARRANGE
            RevokeDocumentRequest request = new RevokeDocumentRequest();
            request.setRequesterCode("AGENCY-A");
            request.setReason("Thu hồi do lỗi nội dung");

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);

            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L)
                    .status(DocumentStatus.ACTIVE)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));
            when(exchangeTransactionsRepository.findByDocumentId(1L)).thenReturn(List.of());

            // Mock save trả về transaction có ID
            when(exchangeTransactionsRepository.save(any(ExchangeTransactions.class)))
                    .thenAnswer(inv -> {
                        ExchangeTransactions t = inv.getArgument(0);
                        return ExchangeTransactions.builder()
                                .id(99L)
                                .transactionCode(t.getTransactionCode())
                                .currentStatus(t.getCurrentStatus())
                                .senderOrgId(t.getSenderOrgId())
                                .receiverOrgId(t.getReceiverOrgId())
                                .documentId(t.getDocumentId())
                                .priority(t.getPriority())
                                .signatureStatus(t.getSignatureStatus())
                                .build();
                    });

            // ACT
            var response = exchangeService.revokeDocument("DOC-001", request);

            // ASSERT — response có transactionCode
            assertThat(response).isNotNull();
            assertThat(response.getTransactionCode()).isNotBlank();

            // Document được save với status = RECALLED
            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(docCaptor.capture());
            assertThat(docCaptor.getValue().getStatus()).isEqualTo(DocumentStatus.RECALLED);

            // Transaction REVOKE được tạo đúng
            ArgumentCaptor<ExchangeTransactions> txnCaptor = ArgumentCaptor.forClass(ExchangeTransactions.class);
            verify(exchangeTransactionsRepository).save(txnCaptor.capture());
            ExchangeTransactions savedTxn = txnCaptor.getValue();
            assertThat(savedTxn.getDocumentId()).isEqualTo(1L);
            assertThat(savedTxn.getSenderOrgId()).isEqualTo(10L);
            assertThat(savedTxn.getCurrentStatus()).isEqualTo(TransactionStatus.RECEIVED);
            assertThat(savedTxn.getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        }
    }

    // =================================================================
    // 4. updateDocument
    // =================================================================
    @Nested
    @DisplayName("updateDocument() - Cập nhật văn bản")
    class UpdateDocumentTests {

        @Test
        @DisplayName("Thất bại: Requester không phải cơ quan gửi gốc → ForbiddenException")
        void updateDocument_RequesterIsNotOriginalSender_ShouldThrow() {
            // ARRANGE
            UpdateDocumentRequest request = new UpdateDocumentRequest();
            request.setRequesterCode("AGENCY-B");

            when(registryService.getOrganizationIdByCode("AGENCY-B")).thenReturn(20L);

            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L) // Cơ quan gửi gốc là ID 10L, không phải 20L
                    .status(DocumentStatus.ACTIVE)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.updateDocument("DOC-001", request))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("cơ quan gửi gốc");
        }

        @Test
        @DisplayName("Thất bại: Document không ở trạng thái ACTIVE → BusinessLogicException")
        void updateDocument_DocumentNotActive_ShouldThrow() {
            // ARRANGE
            UpdateDocumentRequest request = new UpdateDocumentRequest();
            request.setRequesterCode("AGENCY-A");

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);

            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L)
                    .status(DocumentStatus.RECALLED) // Đã bị thu hồi
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));

            // ACT & ASSERT
            assertThatThrownBy(() -> exchangeService.updateDocument("DOC-001", request))
                    .isInstanceOf(BusinessLogicException.class)
                    .hasMessageContaining("RECALLED");
        }

        @Test
        @DisplayName("Thành công: Tạo version mới (versionNo tăng từ 1 lên 2)")
        void updateDocument_ValidRequest_ShouldIncrementVersionNo() {
            // ARRANGE
            UpdateDocumentRequest request = new UpdateDocumentRequest();
            request.setRequesterCode("AGENCY-A");
            request.setTitle("Tiêu đề mới");
            request.setUpdateReason("Chỉnh sửa tiêu đề");
            request.setStoragePath("minio/docs/DOC-001-v2.pdf");
            request.setPayloadChecksum("abc123");

            when(registryService.getOrganizationIdByCode("AGENCY-A")).thenReturn(10L);

            Document document = Document.builder()
                    .id(1L)
                    .documentCode("DOC-001")
                    .senderOrgId(10L)
                    .status(DocumentStatus.ACTIVE)
                    .currentVersion(1)
                    .build();
            when(documentRepository.findByDocumentCode("DOC-001")).thenReturn(Optional.of(document));

            // Đang có version 1 trong DB
            DocumentVersion existingVersion = DocumentVersion.builder()
                    .documentId(1L)
                    .versionNo(1)
                    .storagePath("minio/docs/DOC-001-v1.pdf")
                    .checksum("old-checksum")
                    .build();
            when(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(1L))
                    .thenReturn(Optional.of(existingVersion));

            // ACT
            exchangeService.updateDocument("DOC-001", request);

            // ASSERT — DocumentVersion mới được tạo với versionNo = 2
            ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
            verify(documentVersionRepository).save(versionCaptor.capture());
            DocumentVersion newVersion = versionCaptor.getValue();
            assertThat(newVersion.getVersionNo()).isEqualTo(2);
            assertThat(newVersion.getStoragePath()).isEqualTo("minio/docs/DOC-001-v2.pdf");
            assertThat(newVersion.getChecksum()).isEqualTo("abc123");
            assertThat(newVersion.getUpdateReason()).isEqualTo("Chỉnh sửa tiêu đề");

            // Document được save với currentVersion = 2
            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(docCaptor.capture());
            assertThat(docCaptor.getValue().getCurrentVersion()).isEqualTo(2);
        }
    }
}

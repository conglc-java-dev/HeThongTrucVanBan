package com.TrucVanban.shared;

import com.TrucVanban.BaseIT;
import com.TrucVanban.shared.config.RabbitMQConfig;
import com.TrucVanban.shared.dlq.repository.FailedMessageRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-06: Kiểm thử Dead Letter Queue (DLX) Pipeline — RabbitMQ + PostgreSQL phối hợp.
 *
 * <p>Luồng được kiểm thử:
 *   Message bắn vào DLX Queue → DlxConsumer xử lý →
 *   Nếu retryCount >= MAX (3): lưu vào failed_messages (PostgreSQL) + đẩy vào DLQ.
 *   Nếu retryCount < MAX:      forward sang retry queue tương ứng.
 *
 * <p>Sử dụng Awaitility để chờ async consumer xử lý message mà không cần Thread.sleep cứng.
 * Awaitility đã có sẵn trong spring-boot-starter-test (thư viện phụ trợ của JUnit 5).
 *
 * <p>Tại sao IT này quan trọng hơn Unit Test:
 * Unit test mock cả RabbitMQ và DB. IT này xác nhận DlxConsumer thực sự:
 * 1. Nhận được message từ RabbitMQ broker thật.
 * 2. Đọc đúng header x-retry-count.
 * 3. Gọi failedMessageService.saveFailedMessage() → ghi vào PostgreSQL thật.
 */
@DisplayName("IT-06: Dead Letter Queue — DLX Pipeline (RabbitMQ + PostgreSQL)")
class DeadLetterQueueIT extends BaseIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private FailedMessageRepository failedMessageRepository;

    @BeforeEach
    void setUp() {
        // Dọn failed_messages table
        failedMessageRepository.deleteAll();
        // Purge các queue liên quan để bắt đầu sạch
        rabbitAdmin.purgeQueue(RabbitMQConfig.DOCUMENT_DLX_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.DOCUMENT_DLQ, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.DOCUMENT_RETRY_QUEUE_1, false);
    }

    /**
     * Helper: tạo AMQP Message và bắn thẳng vào DLX Queue.
     * DlxConsumer lắng nghe queue DOCUMENT_DLX_QUEUE.
     */
    private void sendMessageToDlxQueue(String payload, int retryCount) {
        Message message = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setHeader(RabbitMQConfig.HEADER_RETRY_COUNT, retryCount)
                .setHeader("__TypeId__", "com.TrucVanban.routing.dto.request.RoutingRequest")
                .build();
        // Gửi trực tiếp vào DLX exchange → route đến dlx.queue
        rabbitTemplate.send(RabbitMQConfig.DOCUMENT_DLX, RabbitMQConfig.DOCUMENT_DLX_ROUTING_KEY, message);
    }

    @Test
    @DisplayName("Message với retryCount = MAX(3) → DlxConsumer lưu vào failed_messages table")
    void message_maxRetryExceeded_shouldSaveToFailedMessagesTable() {
        // GIVEN: message đã retry đủ 3 lần (MAX_RETRY_COUNT = 3)
        String testPayload = """
                {
                  "transactionCode": "IT-TXN-DLQ-001",
                  "documentCode": "IT-DOC-DLQ-001",
                  "senderCode": "SENDER_IT",
                  "receiverCode": "RECEIVER_IT"
                }
                """;

        // WHEN: bắn message vào DLX queue với retryCount = MAX
        sendMessageToDlxQueue(testPayload, RabbitMQConfig.MAX_RETRY_COUNT);

        // THEN: Dùng Awaitility để chờ async DlxConsumer xử lý (tối đa 10s)
        // Không dùng Thread.sleep cứng → tránh flaky test khi CI chậm
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    long count = failedMessageRepository.count();
                    assertThat(count)
                            .as("Phải có đúng 1 record trong bảng failed_messages sau khi message hết retry")
                            .isGreaterThanOrEqualTo(1L);
                });

        // THEN: Kiểm tra nội dung record lưu đúng
        var failedMessages = failedMessageRepository.findAll();
        assertThat(failedMessages).hasSize(1);
        assertThat(failedMessages.get(0).getRetryCount())
                .as("Retry count phải được ghi đúng = MAX_RETRY_COUNT")
                .isEqualTo(RabbitMQConfig.MAX_RETRY_COUNT);
        assertThat(failedMessages.get(0).getPayload())
                .as("Payload phải được lưu vào DB")
                .isNotBlank();
    }

    @Test
    @DisplayName("Message với retryCount = 0 → DlxConsumer forward sang retry queue 1, KHÔNG lưu failed_messages")
    void message_belowMaxRetry_shouldRouteToRetryQueue1() {
        // GIVEN: message mới fail lần đầu (retryCount = 0)
        String testPayload = """
                {
                  "transactionCode": "IT-TXN-RETRY-001",
                  "documentCode": "IT-DOC-RETRY-001",
                  "senderCode": "SENDER_IT",
                  "receiverCode": "RECEIVER_IT"
                }
                """;

        // WHEN: bắn message vào DLX queue với retryCount = 0
        sendMessageToDlxQueue(testPayload, 0);

        // THEN: Chờ DlxConsumer xử lý và forward sang retry queue 1
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    // Kiểm tra message xuất hiện trong retry queue 1
                    // (receive với timeout ngắn, nếu chưa có thì Awaitility sẽ retry)
                    // Dùng RabbitAdmin để đếm message count an toàn hơn
                    var queueProperties = rabbitAdmin.getQueueProperties(
                            RabbitMQConfig.DOCUMENT_RETRY_QUEUE_1);
                    assertThat(queueProperties).isNotNull();
                    Integer messageCount = (Integer) queueProperties.get("QUEUE_MESSAGE_COUNT");
                    assertThat(messageCount)
                            .as("Retry queue 1 phải có ít nhất 1 message sau khi DlxConsumer forward")
                            .isGreaterThanOrEqualTo(1);
                });

        // THEN: failed_messages KHÔNG có record nào (chưa hết retry)
        assertThat(failedMessageRepository.count())
                .as("Không được có record trong failed_messages khi message vẫn còn được retry")
                .isEqualTo(0L);
    }
}

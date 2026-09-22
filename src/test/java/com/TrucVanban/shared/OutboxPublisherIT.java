package com.TrucVanban.shared;

import com.TrucVanban.BaseIT;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.enums.DocumentStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.shared.config.RabbitMQConfig;
import com.TrucVanban.shared.outbox.OutboxEventConstants;
import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.enums.OutboxEventStatus;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import com.TrucVanban.shared.outbox.service.OutboxEventPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-05: Kiểm thử Outbox → RabbitMQ Publisher.
 *
 * <p>Xác nhận rằng khi OutboxEventPublisherService.publishPendingEvents() được gọi:
 * 1. OutboxEvent có status NEW được pick up.
 * 2. Message được gửi lên RabbitMQ exchange thật (document.exchange).
 * 3. DB cập nhật OutboxEvent.status → PROCESSED.
 *
 * <p>Scheduler bị disable trong profile "it" (fixed-delay-ms = 999999999).
 * Bài test gọi publishPendingEvents() thủ công để có kiểm soát hoàn toàn.
 *
 * <p>Lý do quan trọng: Unit test mock rabbitTemplate.convertAndSend() → không thực sự
 * biết message có đến RabbitMQ hay không. IT này xác nhận điều đó với broker thật.
 */
@DisplayName("IT-05: Outbox Publisher — Outbox Event → RabbitMQ")
class OutboxPublisherIT extends BaseIT {

    @Autowired
    private OutboxEventPublisherService outboxEventPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ExchangeTransactionsRepository exchangeTransactionsRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization sender;
    private Organization receiver;
    private Document testDocument;
    private ExchangeTransactions testTransaction;

    @BeforeEach
    void setUp() {
        // Dọn outbox events cũ
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(e -> "EXCHANGE_TRANSACTION".equals(e.getAggregateType()))
                .toList());

        // Purge RabbitMQ queue để tránh message cũ ảnh hưởng count
        rabbitAdmin.purgeQueue(RabbitMQConfig.DOCUMENT_EXCHANGE_QUEUE, false);

        // Dọn exchange transactions + documents cũ
        exchangeTransactionsRepository.findAll().stream()
                .filter(t -> "IT-OUTBOX-DOC-001".equals(
                        documentRepository.findById(t.getDocumentId())
                                .map(Document::getDocumentCode).orElse("")))
                .forEach(exchangeTransactionsRepository::delete);
        documentRepository.findAll().stream()
                .filter(d -> d.getDocumentCode().startsWith("IT-OUTBOX-"))
                .forEach(documentRepository::delete);

        // Dọn + seed organizations
        organizationRepository.findByCode("IT_OUT_SENDER").ifPresent(organizationRepository::delete);
        organizationRepository.findByCode("IT_OUT_RECEIVER").ifPresent(organizationRepository::delete);

        sender = organizationRepository.save(Organization.builder()
                .code("IT_OUT_SENDER").name("Sender Outbox IT")
                .receiveEndpoint("https://out-sender.example.com/receive")
                .status(OrganizationStatus.ACTIVE).build());

        receiver = organizationRepository.save(Organization.builder()
                .code("IT_OUT_RECEIVER").name("Receiver Outbox IT")
                .receiveEndpoint("https://out-receiver.example.com/receive")
                .status(OrganizationStatus.ACTIVE).build());

        // Seed document + transaction (cần để OutboxPublisher rebuild payload)
        testDocument = documentRepository.save(Document.builder()
                .documentCode("IT-OUTBOX-DOC-001")
                .senderOrgId(sender.getId())
                .status(DocumentStatus.ACTIVE)
                .title("Test Outbox Document")
                .documentType("OFFICIAL_DISPATCH")
                .build());

        testTransaction = exchangeTransactionsRepository.save(ExchangeTransactions.builder()
                .documentId(testDocument.getId())
                .senderOrgId(sender.getId())
                .receiverOrgId(receiver.getId())
                .transactionCode("IT-TXN-OUTBOX-001")
                .currentStatus(TransactionStatus.VALIDATED)
                .priority(1)
                .build());
    }

    @Test
    @DisplayName("OutboxEvent NEW → publishPendingEvents() → message gửi vào RabbitMQ, DB status = PROCESSED")
    void publishPendingEvents_happyPath_shouldSendToRabbitAndMarkProcessed() throws Exception {
        // GIVEN: tạo OutboxEvent với status NEW và payload đủ để Publisher xử lý
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("transactionCode", testTransaction.getTransactionCode());
        payload.put("documentCode", testDocument.getDocumentCode());
        payload.put("title", testDocument.getTitle());
        payload.put("documentType", testDocument.getDocumentType());
        payload.put("storagePath", "it-outbox/doc-001/v1.pdf");
        payload.put("versionNo", 1);
        payload.put("senderCode", sender.getCode());
        payload.put("senderName", sender.getName());
        payload.put("receiverCode", receiver.getCode());
        payload.put("receiverName", receiver.getName());
        payload.put("receiveEndpoint", receiver.getReceiveEndpoint());
        payload.put("priority", 1);

        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(OutboxEventConstants.AGGREGATE_TYPE_EXCHANGE_TRANSACTION)
                .aggregateId(testTransaction.getId())
                .eventType(OutboxEventConstants.EVENT_TYPE_ROUTING_REQUEST)
                .payload(payload)
                .status(OutboxEventStatus.NEW)
                .build());

        // Dừng listener container để RoutingConsumer không tự động consume message trước khi test assert
        rabbitListenerEndpointRegistry.stop();
        try {
            // WHEN: gọi publisher thủ công (scheduler bị disable trong profile it)
            outboxEventPublisherService.publishPendingEvents();

            // THEN — OutboxEvent trong DB phải chuyển sang PROCESSED
            OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertThat(updated.getStatus())
                    .as("OutboxEvent phải được đánh dấu PROCESSED sau khi publish thành công")
                    .isEqualTo(OutboxEventStatus.PROCESSED);
            assertThat(updated.getProcessedAt())
                    .as("processedAt phải được set")
                    .isNotNull();

            // THEN — Message thực sự có mặt trong queue RabbitMQ
            // (Dùng receive với timeout ngắn để đọc message từ queue)
            Message receivedMessage = rabbitTemplate.receive(
                    RabbitMQConfig.DOCUMENT_EXCHANGE_QUEUE, 5000L);
            assertThat(receivedMessage)
                    .as("Phải có ít nhất 1 message trong queue '%s' sau khi publish",
                            RabbitMQConfig.DOCUMENT_EXCHANGE_QUEUE)
                    .isNotNull();
        } finally {
            rabbitListenerEndpointRegistry.start();
        }
    }
}

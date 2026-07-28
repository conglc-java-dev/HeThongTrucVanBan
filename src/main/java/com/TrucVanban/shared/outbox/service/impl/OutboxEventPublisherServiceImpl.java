package com.TrucVanban.shared.outbox.service.impl;

import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.enums.TransactionStatus;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.shared.config.RabbitMQConfig;
import com.TrucVanban.shared.outbox.OutboxEventConstants;
import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.enums.OutboxEventStatus;
import com.TrucVanban.shared.outbox.repository.OutboxEventRepository;
import com.TrucVanban.shared.outbox.service.OutboxEventPublisherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OutboxEventPublisherServiceImpl implements OutboxEventPublisherService {

    private static final List<Integer> RETRY_DELAYS_IN_MINUTES = List.of(5, 15, 30);

    OutboxEventRepository outboxEventRepository;
    ExchangeTransactionsRepository exchangeTransactionsRepository;
    RabbitTemplate rabbitTemplate;
    ObjectMapper objectMapper;

    @Override
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findAndLockEvent(OutboxEventStatus.NEW.name());

        for (OutboxEvent event : pendingEvents) {
            try {
                publish(event);
                event.markProcessed();
            } catch (Exception e) {
                handleRetry(event, e);
                log.error("[outbox] Lỗi publish outbox event: eventId={}, error={}", event.getEventId(), e.getMessage(), e);
            }
        }
    }

    private void publish(OutboxEvent event) {
        if (!OutboxEventConstants.EVENT_TYPE_ROUTING_REQUEST.equals(event.getEventType())) {
            throw new IllegalArgumentException("Unsupported outbox event type: " + event.getEventType());
        }

        RoutingRequest routingRequest = objectMapper.convertValue(event.getPayload(), RoutingRequest.class);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.DOCUMENT_EXCHANGE,
                RabbitMQConfig.DOCUMENT_EXCHANGE_ROUTING_KEY,
                routingRequest
        );
        markTransactionRouted(event);
        log.info("[outbox] Đã publish routing message: eventId={}, transactionCode={}",
                event.getEventId(), routingRequest.getTransactionCode());
    }

    private void markTransactionRouted(OutboxEvent event) {
        ExchangeTransactions transaction = exchangeTransactionsRepository.findById(event.getAggregateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Không tìm thấy giao dịch cho outbox event: " + event.getEventId()));

        transaction.setCurrentStatus(TransactionStatus.ROUTED);
        exchangeTransactionsRepository.save(transaction);
    }

    private void handleRetry(OutboxEvent event, Exception exception) {
        int currentRetryCount = event.getRetryCountOrDefault();
        String lastError = exception.getMessage();

        if (currentRetryCount >= RETRY_DELAYS_IN_MINUTES.size()) {
            event.markFailed(lastError);
            return;
        }

        int nextRetryCount = currentRetryCount + 1;
        int delayInMinutes = RETRY_DELAYS_IN_MINUTES.get(currentRetryCount);
        event.markRetry(nextRetryCount, delayInMinutes, lastError);
    }
}

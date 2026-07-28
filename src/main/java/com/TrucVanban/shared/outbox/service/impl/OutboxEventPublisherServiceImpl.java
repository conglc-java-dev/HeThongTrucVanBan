package com.TrucVanban.shared.outbox.service.impl;

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

    OutboxEventRepository outboxEventRepository;
    RabbitTemplate rabbitTemplate;
    ObjectMapper objectMapper;

    @Override
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.NEW);

        for (OutboxEvent event : pendingEvents) {
            publish(event);
            event.markProcessed();
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
        log.info("[outbox] Đã publish routing message: eventId={}, transactionCode={}",
                event.getEventId(), routingRequest.getTransactionCode());
    }
}

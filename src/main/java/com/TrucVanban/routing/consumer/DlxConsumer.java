package com.TrucVanban.routing.consumer;

import com.TrucVanban.shared.config.RabbitMQConfig;
import com.TrucVanban.shared.dlq.service.FailedMessageService;
import com.rabbitmq.client.Channel;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DlxConsumer {

    private static final Map<Integer, String> RETRY_ROUTING_KEYS = Map.of(
            0, RabbitMQConfig.RETRY_ROUTING_KEY_1,
            1, RabbitMQConfig.RETRY_ROUTING_KEY_2,
            2, RabbitMQConfig.RETRY_ROUTING_KEY_3
    );

    RabbitTemplate rabbitTemplate;
    FailedMessageService failedMessageService;

    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_DLX_QUEUE)
    public void handleDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retryCount = getRetryCount(message);

        try {
            if (retryCount >= RabbitMQConfig.MAX_RETRY_COUNT) {
                sendToDeadLetterQueue(message, retryCount);
            } else {
                sendToRetryQueue(message, retryCount);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[dlx] Lỗi xử lý dead letter message: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void sendToRetryQueue(Message originalMessage, int currentRetryCount) {
        int nextRetryCount = currentRetryCount + 1;
        String routingKey = RETRY_ROUTING_KEYS.get(currentRetryCount);

        Message retryMessage = MessageBuilder
                .withBody(originalMessage.getBody())
                .copyHeaders(cleanHeaders(originalMessage.getMessageProperties()))
                .setContentType(originalMessage.getMessageProperties().getContentType())
                .setContentEncoding(originalMessage.getMessageProperties().getContentEncoding())
                .setHeader(RabbitMQConfig.HEADER_RETRY_COUNT, nextRetryCount)
                .setHeader("__TypeId__", originalMessage.getMessageProperties().getHeader("__TypeId__"))
                .build();

        rabbitTemplate.send(RabbitMQConfig.DOCUMENT_RETRY_EXCHANGE, routingKey, retryMessage);

        log.info("[dlx] Đã gửi message vào retry queue: retryCount={}/{}, routingKey={}",
                nextRetryCount, RabbitMQConfig.MAX_RETRY_COUNT, routingKey);
    }

    private void sendToDeadLetterQueue(Message originalMessage, int retryCount) {
        String errorMessage = extractLastError(originalMessage);

        rabbitTemplate.send(RabbitMQConfig.DOCUMENT_DLQ, MessageBuilder
                .withBody(originalMessage.getBody())
                .copyHeaders(cleanHeaders(originalMessage.getMessageProperties()))
                .setContentType(originalMessage.getMessageProperties().getContentType())
                .setContentEncoding(originalMessage.getMessageProperties().getContentEncoding())
                .setHeader(RabbitMQConfig.HEADER_RETRY_COUNT, retryCount)
                .setHeader("__TypeId__", originalMessage.getMessageProperties().getHeader("__TypeId__"))
                .build());

        failedMessageService.saveFailedMessage(originalMessage, retryCount, errorMessage);

        log.warn("[dlx] Message đã hết retry ({}/{}), đẩy vào DLQ: {}",
                retryCount, RabbitMQConfig.MAX_RETRY_COUNT, RabbitMQConfig.DOCUMENT_DLQ);
    }

    private int getRetryCount(Message message) {
        Object retryHeader = message.getMessageProperties().getHeader(RabbitMQConfig.HEADER_RETRY_COUNT);
        return retryHeader instanceof Number ? ((Number) retryHeader).intValue() : 0;
    }

    private String extractLastError(Message message) {
        Object xDeath = message.getMessageProperties().getHeader("x-death");
        if (xDeath != null) {
            return "Message expired after " + RabbitMQConfig.MAX_RETRY_COUNT + " retries. x-death: " + xDeath;
        }
        return "Message failed after " + RabbitMQConfig.MAX_RETRY_COUNT + " retries";
    }

    /**
     * Xóa các header x-death do RabbitMQ tự thêm khi DLX routing,
     * tránh tích lũy header qua nhiều lần retry.
     */
    private Map<String, Object> cleanHeaders(MessageProperties properties) {
        Map<String, Object> headers = new java.util.HashMap<>(properties.getHeaders());
        headers.remove("x-death");
        headers.remove("x-first-death-exchange");
        headers.remove("x-first-death-queue");
        headers.remove("x-first-death-reason");
        return headers;
    }
}

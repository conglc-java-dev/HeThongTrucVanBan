package com.TrucVanban.routing.consumer;

import com.TrucVanban.routing.dto.request.RoutingRequest;
import com.TrucVanban.routing.service.RoutingService;
import com.TrucVanban.shared.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RoutingConsumer {

    RoutingService routingService;
    Jackson2JsonMessageConverter messageConverter;

    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_EXCHANGE_QUEUE)
    public void dispatchToAgencyB(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retryCount = getRetryCount(message);

        try {
            RoutingRequest request = (RoutingRequest) messageConverter.fromMessage(message);
            log.info("[routing] Nhận routing message: transactionCode={}, retryCount={}",
                    request.getTransactionCode(), retryCount);

            routingService.dispatch(request);
            channel.basicAck(deliveryTag, false);

            log.info("[routing] Xử lý thành công: transactionCode={}", request.getTransactionCode());
        } catch (Exception e) {
            log.error("[routing] Xử lý thất bại (retryCount={}): {}", retryCount, e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private int getRetryCount(Message message) {
        Object retryHeader = message.getMessageProperties().getHeader(RabbitMQConfig.HEADER_RETRY_COUNT);
        return retryHeader instanceof Number ? ((Number) retryHeader).intValue() : 0;
    }
}

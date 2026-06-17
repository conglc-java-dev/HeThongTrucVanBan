package com.TrucVanban.exchange.consumer;

import com.TrucVanban.shared.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ExchangeWorker {

//    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_EXCHANGE_QUEUE)
//    public void saveDocumentToMinIO() {
//
//    }
}

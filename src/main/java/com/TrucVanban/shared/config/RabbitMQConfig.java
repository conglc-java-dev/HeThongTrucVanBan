package com.TrucVanban.shared.config;

import com.TrucVanban.routing.dto.request.RoutingRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    // ===== Main Exchange & Queue =====
    public static final String DOCUMENT_EXCHANGE = "document.exchange";
    public static final String DOCUMENT_EXCHANGE_QUEUE = "document.exchange.queue";
    public static final String DOCUMENT_EXCHANGE_ROUTING_KEY = "document.exchange";

    // ===== Dead Letter Exchange & Queue =====
    public static final String DOCUMENT_DLX = "document.dlx";
    public static final String DOCUMENT_DLX_QUEUE = "document.dlx.queue";
    public static final String DOCUMENT_DLX_ROUTING_KEY = "document.dlx";
    public static final String DOCUMENT_DLQ = "document.dlq";

    // ===== Retry Exchange & Queues =====
    public static final String DOCUMENT_RETRY_EXCHANGE = "document.retry.exchange";
    public static final String DOCUMENT_RETRY_QUEUE_1 = "document.retry.queue.1";
    public static final String DOCUMENT_RETRY_QUEUE_2 = "document.retry.queue.2";
    public static final String DOCUMENT_RETRY_QUEUE_3 = "document.retry.queue.3";
    public static final String RETRY_ROUTING_KEY_1 = "retry.1";
    public static final String RETRY_ROUTING_KEY_2 = "retry.2";
    public static final String RETRY_ROUTING_KEY_3 = "retry.3";

    // ===== Retry TTL (milliseconds) =====
    public static final int RETRY_TTL_1 = 10_000;   // 10 seconds
    public static final int RETRY_TTL_2 = 60_000;   // 60 seconds
    public static final int RETRY_TTL_3 = 300_000;  // 5 minutes

    // ===== Retry Header =====
    public static final String HEADER_RETRY_COUNT = "x-retry-count";
    public static final int MAX_RETRY_COUNT = 3;

    private static final String ROUTING_REQUEST_PACKAGE = RoutingRequest.class.getPackageName();

    // ==================== Exchanges ====================

    @Bean
    public TopicExchange documentExchange() {
        return new TopicExchange(DOCUMENT_EXCHANGE);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DOCUMENT_DLX);
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(DOCUMENT_RETRY_EXCHANGE);
    }

    // ==================== Queues ====================

    @Bean
    public Queue documentExchangeQueue() {
        return QueueBuilder.durable(DOCUMENT_EXCHANGE_QUEUE)
                .withArgument("x-dead-letter-exchange", DOCUMENT_DLX)
                .withArgument("x-dead-letter-routing-key", DOCUMENT_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DOCUMENT_DLX_QUEUE).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DOCUMENT_DLQ).build();
    }

    @Bean
    public Queue retryQueue1() {
        return QueueBuilder.durable(DOCUMENT_RETRY_QUEUE_1)
                .withArgument("x-message-ttl", RETRY_TTL_1)
                .withArgument("x-dead-letter-exchange", DOCUMENT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DOCUMENT_EXCHANGE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue retryQueue2() {
        return QueueBuilder.durable(DOCUMENT_RETRY_QUEUE_2)
                .withArgument("x-message-ttl", RETRY_TTL_2)
                .withArgument("x-dead-letter-exchange", DOCUMENT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DOCUMENT_EXCHANGE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue retryQueue3() {
        return QueueBuilder.durable(DOCUMENT_RETRY_QUEUE_3)
                .withArgument("x-message-ttl", RETRY_TTL_3)
                .withArgument("x-dead-letter-exchange", DOCUMENT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DOCUMENT_EXCHANGE_ROUTING_KEY)
                .build();
    }

    // ==================== Bindings ====================

    @Bean
    public Binding documentExchangeBinding() {
        return BindingBuilder.bind(documentExchangeQueue())
                .to(documentExchange())
                .with(DOCUMENT_EXCHANGE_ROUTING_KEY);
    }

    @Bean
    public Binding dlxQueueBinding() {
        return BindingBuilder.bind(dlxQueue())
                .to(deadLetterExchange())
                .with(DOCUMENT_DLX_ROUTING_KEY);
    }

    @Bean
    public Binding retryQueue1Binding() {
        return BindingBuilder.bind(retryQueue1())
                .to(retryExchange())
                .with(RETRY_ROUTING_KEY_1);
    }

    @Bean
    public Binding retryQueue2Binding() {
        return BindingBuilder.bind(retryQueue2())
                .to(retryExchange())
                .with(RETRY_ROUTING_KEY_2);
    }

    @Bean
    public Binding retryQueue3Binding() {
        return BindingBuilder.bind(retryQueue3())
                .to(retryExchange())
                .with(RETRY_ROUTING_KEY_3);
    }

    // ==================== Converter & Template ====================

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages(ROUTING_REQUEST_PACKAGE);

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setMandatory(true);
        template.setReturnsCallback(returned ->
                log.error("RabbitMQ returned message: exchange={}, routingKey={}, replyCode={}, replyText={}",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText()
                )
        );
        return template;
    }
}

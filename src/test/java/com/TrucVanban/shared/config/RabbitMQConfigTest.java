package com.TrucVanban.shared.config;

import com.TrucVanban.routing.dto.request.RoutingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void messageConverterShouldDeserializeRoutingRequestFromTrustedPackage() {
        Jackson2JsonMessageConverter converter = new RabbitMQConfig().messageConverter();
        RoutingRequest expected = RoutingRequest.builder()
                .transactionCode("TX-001")
                .documentCode("DOC-001")
                .receiverCode("AGENCY-B")
                .build();

        Message message = converter.toMessage(expected, new MessageProperties());

        Object convertedMessage = converter.fromMessage(message);

        assertThat(convertedMessage).isInstanceOf(RoutingRequest.class);
        RoutingRequest actual = (RoutingRequest) convertedMessage;
        assertThat(actual.getTransactionCode()).isEqualTo(expected.getTransactionCode());
        assertThat(actual.getDocumentCode()).isEqualTo(expected.getDocumentCode());
        assertThat(actual.getReceiverCode()).isEqualTo(expected.getReceiverCode());
    }
}

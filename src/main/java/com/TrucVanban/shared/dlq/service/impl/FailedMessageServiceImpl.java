package com.TrucVanban.shared.dlq.service.impl;

import com.TrucVanban.shared.dlq.entity.FailedMessage;
import com.TrucVanban.shared.dlq.repository.FailedMessageRepository;
import com.TrucVanban.shared.dlq.service.FailedMessageService;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class FailedMessageServiceImpl implements FailedMessageService {

    FailedMessageRepository failedMessageRepository;

    @Override
    @Transactional
    public void saveFailedMessage(Message message, int retryCount, String errorMessage) {
        MessageProperties properties = message.getMessageProperties();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        FailedMessage failedMessage = FailedMessage.builder()
                .messageId(properties.getMessageId())
                .exchange(properties.getReceivedExchange())
                .routingKey(properties.getReceivedRoutingKey())
                .payload(payload)
                .errorMessage(errorMessage)
                .retryCount(retryCount)
                .failedAt(LocalDateTime.now())
                .build();

        failedMessageRepository.save(failedMessage);
        log.info("[dlq] Đã lưu failed message vào DB: id={}, messageId={}",
                failedMessage.getId(), failedMessage.getMessageId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FailedMessage> getFailedMessages() {
        return failedMessageRepository.findAllByOrderByFailedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public FailedMessage getFailedMessageById(Long id) {
        return failedMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy failed message với id: " + id));
    }
}

package com.TrucVanban.shared.dlq.service;

import com.TrucVanban.shared.dlq.entity.FailedMessage;
import org.springframework.amqp.core.Message;

import java.util.List;

public interface FailedMessageService {

    /**
     * Lưu message thất bại vào DB khi đã hết lượt retry.
     *
     * @param message      raw AMQP message
     * @param retryCount   số lần retry đã thực hiện
     * @param errorMessage mô tả lỗi
     */
    void saveFailedMessage(Message message, int retryCount, String errorMessage);

    /**
     * Lấy tất cả failed messages, sắp xếp theo thời gian mới nhất.
     */
    List<FailedMessage> getFailedMessages();

    /**
     * Lấy chi tiết 1 failed message theo ID.
     */
    FailedMessage getFailedMessageById(Long id);
}

package com.TrucVanban.shared.dlq.controller;

import com.TrucVanban.shared.ResponseData;
import com.TrucVanban.shared.dlq.entity.FailedMessage;
import com.TrucVanban.shared.dlq.service.FailedMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
@Tag(name = "DLQ Monitoring", description = "API quản lý và giám sát Dead Letter Queue")
public class FailedMessageController {

    private final FailedMessageService failedMessageService;

    @GetMapping("/messages")
    @Operation(summary = "Lấy danh sách tất cả failed messages")
    public ResponseEntity<ResponseData<List<FailedMessage>>> getFailedMessages() {
        List<FailedMessage> messages = failedMessageService.getFailedMessages();
        ResponseData<List<FailedMessage>> response = ResponseData.<List<FailedMessage>>builder()
                .success(true)
                .message("Lấy danh sách failed messages thành công")
                .data(messages)
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/messages/{id}")
    @Operation(summary = "Lấy chi tiết 1 failed message theo ID")
    public ResponseEntity<ResponseData<FailedMessage>> getFailedMessageById(@PathVariable Long id) {
        FailedMessage message = failedMessageService.getFailedMessageById(id);
        ResponseData<FailedMessage> response = ResponseData.<FailedMessage>builder()
                .success(true)
                .message("Lấy chi tiết failed message thành công")
                .data(message)
                .build();
        return ResponseEntity.ok(response);
    }
}

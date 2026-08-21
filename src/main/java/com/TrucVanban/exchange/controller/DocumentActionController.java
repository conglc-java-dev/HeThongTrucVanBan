package com.TrucVanban.exchange.controller;

import com.TrucVanban.exchange.dto.request.action.InitRecallActionRequest;
import com.TrucVanban.exchange.dto.request.action.InitUpdateActionRequest;
import com.TrucVanban.exchange.dto.response.DocumentActionResponse;
import com.TrucVanban.exchange.service.DocumentActionService;
import com.TrucVanban.shared.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/document-actions")
@RequiredArgsConstructor
public class DocumentActionController {

    private final DocumentActionService documentActionService;

    @PostMapping("/recall")
    @Operation(summary = "Khởi tạo lệnh Thu hồi Văn bản (Chiến dịch biểu quyết)")
    public ResponseEntity<ResponseData<DocumentActionResponse>> initRecallAction(
            @RequestBody @Valid InitRecallActionRequest request) {
        DocumentActionResponse data = documentActionService.initRecallAction(request);
        return ResponseEntity.ok(ResponseData.<DocumentActionResponse>builder()
                .success(true)
                .message("Đã khởi tạo chiến dịch Thu hồi. Đang chờ các bên nhận biểu quyết.")
                .data(data)
                .build());
    }

    @PostMapping("/update")
    @Operation(summary = "Khởi tạo lệnh Cập nhật Văn bản")
    public ResponseEntity<ResponseData<DocumentActionResponse>> initUpdateAction(
            @RequestBody @Valid InitUpdateActionRequest request) {
        DocumentActionResponse data = documentActionService.initUpdateAction(request);
        return ResponseEntity.ok(ResponseData.<DocumentActionResponse>builder()
                .success(true)
                .message("Đã phát lệnh Cập nhật. Đang chờ các bên nhận phản hồi.")
                .data(data)
                .build());
    }
}
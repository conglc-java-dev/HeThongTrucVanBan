package com.TrucVanban.exchange.controller;

import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.ExchangeDocumentResponse;
import com.TrucVanban.exchange.service.ExchangeService;
import com.TrucVanban.shared.ResponseData;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ExchangeController {

    ExchangeService exchangeService;

    @PostMapping(value = "/exchange", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseData<List<ExchangeDocumentResponse>>> exchangeDocument(@ModelAttribute @Valid ExchangeDocumentRequest request) {
        List<ExchangeDocumentResponse> data = exchangeService.exchangeDocument(request);
        ResponseData<List<ExchangeDocumentResponse>> response = ResponseData.<List<ExchangeDocumentResponse>>builder()
                .success(true)
                .message("Tiếp nhận giao dịch thành công. Hệ thống đang đưa vào hàng đợi ưu tiên")
                .data(data)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/ack")
    public ResponseEntity<ResponseData<String>> ack(@RequestBody ReceiveDocumentRequest request) {
        ResponseData<String> response = ResponseData.<String>builder()
                .success(true)
                .message("Ghi nhận trạng thái ACK thành công")
                .build();

        return ResponseEntity.ok(response);
    }
}

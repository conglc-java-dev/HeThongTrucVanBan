package com.TrucVanban.exchange.controller;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.SenderDocumentResponse;
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
    public ResponseEntity<ResponseData<List<SenderDocumentResponse>>> exchangeDocument(@ModelAttribute @Valid ExchangeDocumentRequest request) {
        List<SenderDocumentResponse> data = exchangeService.exchangeDocument(request);
        ResponseData<List<SenderDocumentResponse>> response = ResponseData.<List<SenderDocumentResponse>>builder()
                .success(true)
                .message("Tiếp nhận giao dịch thành công. Hệ thống đang đưa vào hàng đợi ưu tiên")
                .data(data)
                .build();

        return ResponseEntity.ok(response);
    }

}

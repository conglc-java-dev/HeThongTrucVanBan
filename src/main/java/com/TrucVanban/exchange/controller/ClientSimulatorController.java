package com.TrucVanban.exchange.controller;


import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignAndBuildRequest;
import com.TrucVanban.exchange.dto.request.send.SimulateMultiSigRequest;
import com.TrucVanban.exchange.dto.response.FileUploadResponse;
import com.TrucVanban.exchange.service.ClientSimulatorService;
import com.TrucVanban.shared.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/simulator")
@RequiredArgsConstructor
@Tag(name = "Simulator", description = "Công cụ giả lập E-Office Client — Chỉ dùng cho môi trường Dev/Test")
public class ClientSimulatorController {

    private final ClientSimulatorService clientSimulatorService;

    @PostMapping(value = "/test-send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Giả lập Client: Upload file, tự động ký số thật và gửi văn bản")
    public ResponseEntity<?> simulateClientSend(
            @RequestPart("file") MultipartFile file,
            @RequestParam String senderCode,
            @RequestParam List<String> receiverCodes,
            @RequestParam String documentCode,
            @RequestParam String certificateSerialNumber,
            @RequestParam(required = false, defaultValue = "1") Integer priority,
            @RequestParam String idempotencyKey) throws Exception {

        log.info("[Simulator Controller] Tiếp nhận yêu cầu giả lập gửi văn bản từ Client...");

        // Đẩy toàn bộ tác vụ nặng (Upload, Băm, Ký, Gửi) xuống tầng Service
        Object response = clientSimulatorService.processAndSend(
                file, senderCode, receiverCodes, documentCode, certificateSerialNumber, priority, idempotencyKey);

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseData<List<FileUploadResponse>>> uploadFiles(
            @RequestPart("files") MultipartFile[] files) throws Exception {

        List<FileUploadResponse> data = clientSimulatorService.uploadFiles(files);
        return ResponseEntity.ok(ResponseData.<List<FileUploadResponse>>builder()
                .success(true)
                .message("Tải lên và băm file thành công")
                .data(data)
                .build());
    }


    @PostMapping(value = "/sign-and-build-multi-sig", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseData<MultiSignatureRequest>> signAndBuildMultiSig(
            @RequestBody @Valid SimulateMultiSigRequest request) throws Exception {

        log.info("[Simulator Controller] Giả lập ký đa chữ ký: sender={}, role={}",
                request.getCurrentSenderCode(), request.getSignerRole());

        MultiSignatureRequest data = clientSimulatorService.signAndBuildMultiSigPayload(request);

        int stepNumber = (request.getExistingSignatures() != null
                ? request.getExistingSignatures().size() : 0) + 1;

        return ResponseEntity.ok(ResponseData.<MultiSignatureRequest>builder()
                .success(true)
                .message(String.format(
                        "Ký giả lập thành công cho [%s] — Bước #%d (%s). " +
                        "Copy toàn bộ object 'data' và nộp sang POST /api/v1/exchange-documents/signatures",
                        request.getCurrentSenderCode(), stepNumber, request.getSignerRole()))
                .data(data)
                .build());
    }

    @PostMapping(value = "/sign-and-build-payload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseData<ExchangeDocumentRequest>> signAndBuildPayload(
            @RequestBody @Valid SignAndBuildRequest request) throws Exception {

        ExchangeDocumentRequest data = clientSimulatorService.signAndBuildPayload(request);
        return ResponseEntity.ok(ResponseData.<ExchangeDocumentRequest>builder()
                .success(true)
                .message("Ký số thành công. Trả về gói Payload.")
                .data(data)
                .build());
    }
}
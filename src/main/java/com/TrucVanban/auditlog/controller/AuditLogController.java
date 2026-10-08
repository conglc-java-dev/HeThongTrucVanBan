package com.TrucVanban.auditlog.controller;

import com.TrucVanban.auditlog.dto.request.AuditLogFilterRequest;
import com.TrucVanban.auditlog.dto.response.AuditLogPageResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelinePageResponse;
import com.TrucVanban.auditlog.service.AuditQueryService;
import com.TrucVanban.shared.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@Validated
public class AuditLogController {
    private final AuditQueryService auditQueryService;

    @GetMapping
    @Operation(summary = "Tra cứu nhật ký kiểm toán")
    public ResponseEntity<ResponseData<AuditLogPageResponse>> search(
            @Valid @ModelAttribute AuditLogFilterRequest filter) {
        return ok(auditQueryService.search(filter));
    }

    @GetMapping("/timeline")
    @Operation(summary = "Lấy dòng thời gian xử lý")
    public ResponseEntity<ResponseData<AuditTimelinePageResponse>> getTimeline(
            @RequestParam(required = false) @Size(max = 100) String documentCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return timelineResponse(auditQueryService.getTimeline(documentCode, page, size));
    }

    private ResponseEntity<ResponseData<AuditTimelinePageResponse>> timelineResponse(
            AuditTimelinePageResponse data) {
        return ResponseEntity.ok(ResponseData.<AuditTimelinePageResponse>builder()
                .success(true)
                .message("Lấy dòng thời gian xử lý thành công")
                .data(data)
                .build());
    }

    private ResponseEntity<ResponseData<AuditLogPageResponse>> ok(AuditLogPageResponse data) {
        return ResponseEntity.ok(ResponseData.<AuditLogPageResponse>builder()
                .success(true)
                .message("Tra cứu nhật ký kiểm toán thành công")
                .data(data)
                .build());
    }
}

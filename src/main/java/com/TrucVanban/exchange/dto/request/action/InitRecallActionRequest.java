package com.TrucVanban.exchange.dto.request.action;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitRecallActionRequest {
    @NotBlank(message = "Mã văn bản bị thu hồi là bắt buộc")
    private String recalledDocumentCode;

    @NotBlank(message = "Mã văn bản hành động (công văn thu hồi) là bắt buộc")
    private String actionDocumentCode;

    @NotBlank(message = "Mã đơn vị yêu cầu là bắt buộc")
    private String requestedByCode;

    @NotBlank(message = "Lý do thu hồi là bắt buộc")
    private String reason;
}
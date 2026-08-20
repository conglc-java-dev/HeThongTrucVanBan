package com.TrucVanban.exchange.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RevokeDocumentRequest {
    @NotBlank(message = "Mã văn bản là bắt buộc")
    private String documentCode;

    @NotBlank(message = "Mã tổ chức yêu cầu thu hồi là bắt buộc")
    private String requesterCode;

    @NotBlank(message = "Lý do thu hồi là bắt buộc")
    private String reason;
}

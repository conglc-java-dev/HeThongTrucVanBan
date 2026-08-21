package com.TrucVanban.exchange.dto.request.action;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitUpdateActionRequest {
    @NotBlank(message = "Mã văn bản mục tiêu là bắt buộc")
    private String targetDocumentCode;

    @NotBlank(message = "Mã đơn vị yêu cầu là bắt buộc")
    private String requestedByCode;

    @NotNull(message = "Dữ liệu cập nhật là bắt buộc")
    private UpdateData updateData;

    @NotBlank(message = "Lý do cập nhật là bắt buộc")
    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateData {
        private List<String> addedReceivers;
        private List<String> removedReceivers;
        private NewPayload newPayload;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewPayload {
        private String storagePath;
        private String checksum;
    }
}
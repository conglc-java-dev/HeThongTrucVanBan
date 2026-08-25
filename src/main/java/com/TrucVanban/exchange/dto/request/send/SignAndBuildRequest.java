package com.TrucVanban.exchange.dto.request.send;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.util.List;

@Data
public class SignAndBuildRequest {
    @NotBlank(message = "Mã cơ quan gửi không được để trống")
    private String senderCode;

    @NotEmpty(message = "Danh sách nơi nhận không được để trống")
    private List<String> receiverCodes;

    @NotBlank(message = "Mã văn bản không được để trống")
    private String documentCode;

    @NotBlank(message = "Số serial chứng thư số không được để trống")
    private String certificateSerialNumber;

    @NotBlank(message = "Đường dẫn lưu trữ (Storage Path) không được để trống")
    private String storagePath;

    @NotBlank(message = "Mã băm file (Checksum) không được để trống")
    private String payloadChecksum;

    private Integer priority = 1;

    private String title;
    private String documentType;
    private String summary;

    /**
     * Ngày/thời gian phát hành văn bản (dd-MM-yyyy, ISO 8601 hoặc YYYY-MM-DD).
     * Ví dụ: "25-08-2026", "2026-08-25" hoặc "2026-08-25T00:00:00+07:00"
     */
    @Pattern(
            regexp = "^(?:\\d{2}-\\d{2}-\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))$",
            message = "Ngày phát hành (issuedDate) phải theo dd-MM-yyyy, ISO 8601 hoặc YYYY-MM-DD"
    )
    private String issuedDate;

    /**
     * Tọa độ vẽ con dấu đỏ lên PDF (tùy chọn).
     * Nếu null hoặc applyVisual=false, bước vẽ dấu được bỏ qua.
     */
    @Valid
    private VisualSignatureRequest stampCoords;

    /** Tọa độ vẽ chữ ký tay lên PDF (tùy chọn). */
    @Valid
    private VisualSignatureRequest signatureCoords;
}

package com.TrucVanban.exchange.dto.request.send;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

package com.TrucVanban.exchange.dto.request.send;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisualSignatureRequest {

    @Builder.Default
    private boolean applyVisual = false;

    /** Số thứ tự trang PDF đặt ảnh (bắt đầu từ 1). */
    @Min(value = 1, message = "pageNumber phải >= 1")
    @Builder.Default
    private int pageNumber = 1;

    /**
     * Tọa độ X góc trên-trái, tính theo ratio chiều rộng canvas.
     * Ví dụ: 0.35 = cách lề trái 35% chiều rộng.
     */
    @DecimalMin(value = "0.0", message = "positionXRatio phải >= 0.0")
    @DecimalMax(value = "1.0", message = "positionXRatio phải <= 1.0")
    private double positionXRatio;

    /**
     * Tọa độ Y góc trên-trái, tính theo ratio chiều cao canvas.
     * Backend tự chuyển sang hệ Bottom-Left của PDF.
     */
    @DecimalMin(value = "0.0", message = "positionYRatio phải >= 0.0")
    @DecimalMax(value = "1.0", message = "positionYRatio phải <= 1.0")
    private double positionYRatio;

    /**
     * Chiều rộng ảnh, tính theo ratio chiều rộng canvas.
     */
    @DecimalMin(value = "0.01", message = "widthRatio phải > 0")
    @DecimalMax(value = "1.0", message = "widthRatio phải <= 1.0")
    private double widthRatio;

    /**
     * Chiều cao ảnh, tính theo ratio chiều cao canvas.
     */
    @DecimalMin(value = "0.01", message = "heightRatio phải > 0")
    @DecimalMax(value = "1.0", message = "heightRatio phải <= 1.0")
    private double heightRatio;
}

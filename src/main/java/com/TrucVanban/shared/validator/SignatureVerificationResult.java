package com.TrucVanban.shared.validator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureVerificationResult {

    private int signatureOrder;

    private String signerCode;

    private boolean valid;

    /**
     * ByteRange trích xuất từ PDF Dictionary /Sig.
     * Định dạng: "[offset1, length1, offset2, length2]"
     */
    private String byteRange;

    private String failureReason;
}

package com.TrucVanban.exchange.dto.request.send;

import com.TrucVanban.shared.validation.AllowedFileType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class ExchangeDocumentRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @NotBlank(message = "Sender code is required")
    private String senderCode;

    @NotEmpty(message = "Receivers is required")
    private List<String> receiverCodes;

    @NotBlank(message = "Document code is required")
    private String documentCode;
    private String title;
    private String documentType;
    private Integer priority;
    private JsonNode extractedMetadata;
    private String summary;

    @NotBlank(message = "Signature is required")
    private String signature;

    @AllowedFileType(types = {
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    })
    private MultipartFile payLoad;

    @SuppressWarnings("unused")
    public void setExtractedMetadata(String extractedMetadataStr) throws JsonProcessingException {
        this.extractedMetadata = MAPPER.readTree(extractedMetadataStr);
    }
}

package com.TrucVanban.exchange.dto.command;

import com.TrucVanban.exchange.enums.DocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentCreateCommand {
    private String documentCode;
    private String title;
    private String summary;
    private JsonNode extractedMetadata;
    private Long senderOrgId;
    private String documentType;
    private Integer currentVersion;
    private DocumentStatus status;
}

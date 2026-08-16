package com.TrucVanban.routing.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRequest {

    // Transaction info
    private String transactionCode;
    private Integer priority;

    // Document info
    private String documentCode;
    private String title;
    private String summary;
    private String documentType;
    private JsonNode extractedMetadata;

    // File info (thay thế query DocumentVersion)
    private String storagePath;
    private Integer versionNo;

    // Sender info (thay thế query Organization)
    private String senderCode;
    private String senderName;

    // Receiver info (thay thế query Organization)
    private String receiverCode;
    private String receiverName;
    private String receiveEndpoint;
}


package com.TrucVanban.exchange.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class TransactionReceivedStatusResponse {
    private String transactionCode;
    private String documentCode;
    private String title;
    private String summary;
    private String documentType;
    private String storagePath;
    private String senderCode;
    private String currentStatus;
    private String issuedDate;
    private JsonNode extractedMetadata;
    private List<timeline> timeline;

    @Data
    @Builder
    public static class timeline{
        private LocalDateTime time;
        private String status;
    }
}

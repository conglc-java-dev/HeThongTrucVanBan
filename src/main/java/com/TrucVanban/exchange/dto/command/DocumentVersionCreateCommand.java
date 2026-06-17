package com.TrucVanban.exchange.dto.command;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentVersionCreateCommand {
    private Long documentId;
    private Integer versionNo;
    private String storagePath;
    private String checksum;
    private Long fileSize;
    private String updateReason;
    private String createdBy;
}

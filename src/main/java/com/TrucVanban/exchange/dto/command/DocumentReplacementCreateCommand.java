package com.TrucVanban.exchange.dto.command;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentReplacementCreateCommand {
    private Long replacementDocumentId;
    private Long replacedDocumentId;
    private String reason;
}

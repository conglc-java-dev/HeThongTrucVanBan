package com.TrucVanban.auditlog.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
public class AuditLogFilterRequest {
    @Size(max = 100)
    private String documentCode;

    @Size(max = 100)
    private String transactionCode;

    @Size(max = 100)
    private String correlationId;

    @Size(max = 100)
    private String action;

    @Size(max = 30)
    private String result;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    @Min(0)
    private int page = 0;

    @Min(1)
    @Max(100)
    private int size = 20;
}

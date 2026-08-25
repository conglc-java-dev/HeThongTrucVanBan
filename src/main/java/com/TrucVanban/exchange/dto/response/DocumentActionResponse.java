package com.TrucVanban.exchange.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentActionResponse {
    private Long actionId;
    private String actionStatus;
    private Integer totalReceiversNotified;
}
package com.TrucVanban.shared;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
public class ResponseData {
    private String message;
    private Object data;
    private boolean success=true;
}

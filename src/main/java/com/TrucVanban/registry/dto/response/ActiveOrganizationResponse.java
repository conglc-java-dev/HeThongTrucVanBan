package com.TrucVanban.registry.dto.response;

import com.TrucVanban.registry.enums.OrganizationStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ActiveOrganizationResponse {

    private String code;
    private String name;
    private OrganizationStatus status;
}

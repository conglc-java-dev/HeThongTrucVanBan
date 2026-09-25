package com.TrucVanban.registry.dto.response;

import com.TrucVanban.registry.enums.OrganizationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SuspendOrganizationResponse {

    private String code;

    private OrganizationStatus status;
}

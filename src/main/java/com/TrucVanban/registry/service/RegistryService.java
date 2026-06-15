package com.TrucVanban.registry.service;

import com.TrucVanban.registry.dto.request.*;
import com.TrucVanban.registry.dto.response.*;

public interface RegistryService {

    RegisterOrganizationResponse registerOrganization(RegisterOrganizationRequest request);

    SuspendOrganizationResponse suspendOrganization(String code, SuspendOrganizationRequest request);

    UpdateEndpointResponse updateEndpoint(String code, UpdateEndpointRequest request);

    UpdateCertificateResponse updateCertificate(String code, CertificateRequest request);

    OrganizationDetailResponse getOrganizationDetail(String code);

    UpdateSlaConfigResponse updateSlaConfig(Integer documentPriority, UpdateSlaConfigRequest request);
}

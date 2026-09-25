package com.TrucVanban.registry.service;

import com.TrucVanban.registry.dto.request.*;
import com.TrucVanban.registry.dto.response.*;
import com.TrucVanban.registry.entity.Certificate;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.OrganizationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RegistryService {

    RegisterOrganizationResponse registerOrganization(RegisterOrganizationRequest request);

    SuspendOrganizationResponse suspendOrganization(String code, SuspendOrganizationRequest request);

    UpdateEndpointResponse updateEndpoint(String code, UpdateEndpointRequest request);

    UpdateCertificateResponse updateCertificate(String code, CertificateRequest request);

    OrganizationDetailResponse getOrganizationDetail(String code);

    UpdateSlaConfigResponse updateSlaConfig(Integer documentPriority, UpdateSlaConfigRequest request);

    Long getOrganizationIdByCode(String code);

    List<Long> getOrganizationIdsByCode(List<String> codes);

    String getOrganizationNameById(Long id);

    Organization getOrganizationById(Long id);

    Integer getMaxReceiveHoursByPriority(Integer documentPriority);

    boolean checkCertificate(String signature, Long organizationId);

    Certificate findActiveCertificateBySerialNumber(String serialNumber);

    List<OrgVisualAssetResponse> getVisualAssets(String orgCode);

    Page<ActiveOrganizationResponse> getOrganizations(OrganizationStatus status, String search, Pageable pageable);
}

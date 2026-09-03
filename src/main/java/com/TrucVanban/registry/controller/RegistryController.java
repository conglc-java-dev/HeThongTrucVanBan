package com.TrucVanban.registry.controller;

import com.TrucVanban.registry.dto.request.*;
import com.TrucVanban.registry.dto.response.*;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.ResponseData;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/registry")
@RequiredArgsConstructor
public class RegistryController {

        private final RegistryService registryService;

        @PostMapping("/organizations")
        @Operation(summary = "Đăng ký tổ chức mới")
        public ResponseEntity<ResponseData<RegisterOrganizationResponse>> registerOrganization(
                        @Valid @RequestBody RegisterOrganizationRequest request) {

                RegisterOrganizationResponse data = registryService.registerOrganization(request);

                return ResponseEntity.status(HttpStatus.CREATED).body(ResponseData
                                .<RegisterOrganizationResponse>builder()
                                .success(true)
                                .message("Đăng ký thành công. Vui lòng chờ được phê duyệt.")
                                .data(data)
                                .build());
        }

        @PatchMapping("/organizations/{code}/status")
        @Operation(summary = "Cập nhật trạng thái tổ chức [phê duyệt, khóa, mở khóa]")
        public ResponseEntity<ResponseData<UpdateOrganizationStatusResponse>> updateOrganizationStatus(
                        @PathVariable String code,
                        @Valid @RequestBody UpdateOrganizationStatusRequest request) {

                UpdateOrganizationStatusResponse data = registryService.updateOrganizationStatus(code, request);

                return ResponseEntity.ok(ResponseData.<UpdateOrganizationStatusResponse>builder()
                                .success(true)
                                .message("Cập nhật trạng thái tổ chức thành công")
                                .data(data)
                                .build());
        }

        @PutMapping("/organizations/{code}/endpoint")
        @Operation(summary = "Cập nhật endpoint nhận vb")
        public ResponseEntity<ResponseData<UpdateEndpointResponse>> updateEndpoint(
                        @PathVariable String code,
                        @Valid @RequestBody UpdateEndpointRequest request) {

                UpdateEndpointResponse data = registryService.updateEndpoint(code, request);

                return ResponseEntity.ok(ResponseData.<UpdateEndpointResponse>builder()
                                .success(true)
                                .message("Cập nhật endpoint thành công")
                                .data(data)
                                .build());
        }

        @PostMapping("/organizations/{code}/certificates")
        @Operation(summary = "Cập nhật chứng thư số certificates")
        public ResponseEntity<ResponseData<UpdateCertificateResponse>> updateCertificate(
                        @PathVariable String code,
                        @Valid @RequestBody CertificateRequest request) {

                UpdateCertificateResponse data = registryService.updateCertificate(code, request);

                return ResponseEntity.ok(ResponseData.<UpdateCertificateResponse>builder()
                                .success(true)
                                .message("Cập nhật chứng thư số thành công")
                                .data(data)
                                .build());
        }

        @GetMapping("/organizations/active")
        @Operation(summary = "Lấy danh sách cơ quan đang hoạt động để chọn nơi nhận")
        public ResponseEntity<ResponseData<List<ActiveOrganizationResponse>>> getActiveOrganizations() {
                List<ActiveOrganizationResponse> data = registryService.getActiveOrganizations();

                return ResponseEntity.ok(ResponseData.<List<ActiveOrganizationResponse>>builder()
                                .success(true)
                                .message("Lấy danh sách cơ quan đang hoạt động thành công")
                                .data(data)
                                .build());
        }

        @GetMapping("/organizations/{code}")
        @Operation(summary = "Tra cứu tt tổ chức")
        public ResponseEntity<ResponseData<OrganizationDetailResponse>> getOrganizationDetail(
                        @PathVariable String code) {

                OrganizationDetailResponse data = registryService.getOrganizationDetail(code);

                return ResponseEntity.ok(ResponseData.<OrganizationDetailResponse>builder()
                                .success(true)
                                .message("Tra cứu thông tin thành công")
                                .data(data)
                                .build());
        }

        /**
         * Lấy danh sách visual assets (con dấu, chữ ký) đang active của cơ quan.
         *
         * <p>Public endpoint — không yêu cầu xác thực.
         * Logic mapping được thực hiện hoàn toàn trong {@code RegistryService}.
         */
        @GetMapping("/organizations/{code}/visual-assets")
        @Operation(summary = "Lấy danh sách con dấu & chữ ký của cơ quan")
        public ResponseEntity<ResponseData<List<OrgVisualAssetResponse>>> getVisualAssets(
                        @PathVariable String code) {

                List<OrgVisualAssetResponse> data = registryService.getVisualAssets(code);

                return ResponseEntity.ok(ResponseData.<List<OrgVisualAssetResponse>>builder()
                                .success(true)
                                .message("Lấy danh sách visual assets thành công")
                                .data(data)
                                .build());
        }

        @GetMapping("/organizations/active")
        @Operation(summary = "Lấy danh sách tổ chức đang hoạt động (ACTIVE)")
        public ResponseEntity<ResponseData<List<ActiveOrganizationResponse>>> getActiveOrganizations() {

                List<ActiveOrganizationResponse> data = registryService.getActiveOrganizations();

                return ResponseEntity.ok(ResponseData.<List<ActiveOrganizationResponse>>builder()
                                .success(true)
                                .message("Lấy danh sách tổ chức thành công")
                                .data(data)
                                .build());
        }
}

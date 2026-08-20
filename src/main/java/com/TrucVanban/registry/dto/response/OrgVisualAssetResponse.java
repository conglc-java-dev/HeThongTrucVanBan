package com.TrucVanban.registry.dto.response;

import com.TrucVanban.registry.enums.AssetType;
import lombok.Builder;
import lombok.Data;

/**
 * Response DTO cho một visual asset của cơ quan.
 * Dùng trong API: GET /registry/organizations/{code}/visual-assets
 */
@Data
@Builder
public class OrgVisualAssetResponse {

    private Long id;

    private AssetType assetType;
    private String assetName;
    private String imageUrl;
    private String signerTitle;

    private boolean isDefault;
}

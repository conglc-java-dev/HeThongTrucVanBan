package com.TrucVanban.registry.repository;

import com.TrucVanban.registry.entity.OrganizationVisualAsset;
import com.TrucVanban.registry.enums.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationVisualAssetRepository extends JpaRepository<OrganizationVisualAsset, Long> {

    List<OrganizationVisualAsset> findByOrganizationCodeAndIsActiveTrue(String orgCode);

    Optional<OrganizationVisualAsset> findFirstByOrganizationCodeAndAssetTypeAndIsDefaultTrueAndIsActiveTrue(
            String orgCode, AssetType assetType);
}

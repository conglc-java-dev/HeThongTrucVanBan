package com.TrucVanban.registry.entity;

import com.TrucVanban.registry.enums.AssetType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Lưu trữ ảnh con dấu và chữ ký của từng cơ quan.
 */
@Entity
@Table(name = "organization_visual_assets")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationVisualAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 30)
    private AssetType assetType;

    @Column(name = "asset_name", nullable = false, length = 100)
    private String assetName;

    /**
     * Object key MinIO (VD: {@code stamps/A_BGDDT.png}) hoặc URL ngoài
     * (bắt đầu bằng {@code http://} hoặc {@code https://}).
     */
    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /** Chức danh người ký — chỉ điền với SIGNATURE_LEADER. */
    @Column(name = "signer_title", length = 100)
    private String signerTitle;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}

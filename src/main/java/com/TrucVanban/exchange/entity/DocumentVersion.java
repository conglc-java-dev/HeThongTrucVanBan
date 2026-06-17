package com.TrucVanban.exchange.entity;

import com.TrucVanban.exchange.dto.command.DocumentVersionCreateCommand;
import com.TrucVanban.shared.utils.NumberUtils;
import com.TrucVanban.shared.utils.StringUtils;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_versions")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "update_reason", columnDefinition = "text")
    private String updateReason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public static DocumentVersion of(DocumentVersionCreateCommand command) {
        if(command == null) throw new IllegalArgumentException("Command must not be null");
        if(NumberUtils.isNullOrNegative(command.getDocumentId())) throw new IllegalArgumentException("Document id must be a positive number");
        if(NumberUtils.isNullOrNegative(command.getVersionNo())) throw new IllegalArgumentException("Version no must be a positive number");
        if(StringUtils.isNullOrBlank(command.getStoragePath())) throw new IllegalArgumentException("Storage path must not be null or empty");
        if(StringUtils.isNullOrBlank(command.getChecksum())) throw new IllegalArgumentException("Checksum must not be null or empty");

        return DocumentVersion.builder()
                .documentId(command.getDocumentId())
                .versionNo(command.getVersionNo())
                .storagePath(command.getStoragePath())
                .checksum(command.getChecksum())
                .fileSize(command.getFileSize())
                .updateReason(command.getUpdateReason())
                .createdBy(command.getCreatedBy())
                .build();
    }
}

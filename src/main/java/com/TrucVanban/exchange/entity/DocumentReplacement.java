package com.TrucVanban.exchange.entity;

import com.TrucVanban.exchange.dto.command.DocumentReplacementCreateCommand;
import com.TrucVanban.shared.utils.NumberUtils;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_replacements")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentReplacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "replacement_document_id", nullable = false)
    private Long replacementDocumentId;

    @Column(name = "replaced_document_id", nullable = false)
    private Long replacedDocumentId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public static DocumentReplacement of(DocumentReplacementCreateCommand command) {
        if (command == null) throw new IllegalArgumentException("Command must not be null");
        if (NumberUtils.isNullOrNegative(command.getReplacementDocumentId())) throw new IllegalArgumentException("ReplacementDocumentId must be a positive number");
        if (NumberUtils.isNullOrNegative(command.getReplacedDocumentId())) throw new IllegalArgumentException("ReplacedDocumentId must be a positive number");

        return DocumentReplacement.builder()
                .replacementDocumentId(command.getReplacementDocumentId())
                .replacedDocumentId(command.getReplacedDocumentId())
                .reason(command.getReason())
                .build();
    }
}

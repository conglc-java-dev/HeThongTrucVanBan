package com.TrucVanban.auditlog.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Integer schemaVersion;

    @Column(name = "source", nullable = false, length = 30, updatable = false)
    private String source;

    @Column(name = "category", nullable = false, length = 50, updatable = false)
    private String category;

    @Column(name = "action", nullable = false, length = 100, updatable = false)
    private String action;

    @Column(name = "result", nullable = false, length = 30, updatable = false)
    private String result;

    @Column(name = "actor_type", length = 30, updatable = false)
    private String actorType;

    @Column(name = "actor_id", length = 100, updatable = false)
    private String actorId;

    @Column(name = "actor_org_id", updatable = false)
    private Long actorOrganizationId;

    @Column(name = "actor_org_code", length = 100, updatable = false)
    private String actorOrganizationCode;

    @Column(name = "target_type", nullable = false, length = 30, updatable = false)
    private String targetType;

    @Column(name = "document_id", updatable = false)
    private Long documentId;

    @Column(name = "document_code", length = 100, updatable = false)
    private String documentCode;

    @Column(name = "transaction_id", updatable = false)
    private Long transactionId;

    @Column(name = "transaction_code", length = 100, updatable = false)
    private String transactionCode;

    @Column(name = "master_transaction_code", length = 100, updatable = false)
    private String masterTransactionCode;

    @Column(name = "request_id", length = 100, updatable = false)
    private String requestId;

    @Column(name = "correlation_id", length = 100, updatable = false)
    private String correlationId;

    @Column(name = "source_ip", length = 64, updatable = false)
    private String sourceIp;

    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    @Column(name = "error_code", length = 100, updatable = false)
    private String errorCode;

    @Column(name = "error_message", length = 1000, updatable = false)
    private String errorMessage;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb", updatable = false)
    private JsonNode detail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb", updatable = false)
    private JsonNode beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb", updatable = false)
    private JsonNode afterState;

    @Column(name = "visibility_scope", nullable = false, length = 50, updatable = false)
    private String visibilityScope;

    @Column(name = "sender_org_id", updatable = false)
    private Long senderOrganizationId;

    @Column(name = "receiver_org_id", updatable = false)
    private Long receiverOrganizationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    @PrePersist
    void initialize() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (eventId == null) eventId = UUID.randomUUID();
        if (schemaVersion == null) schemaVersion = 1;
        if (createdAt == null) createdAt = now;
        if (recordedAt == null) recordedAt = now;
    }
}


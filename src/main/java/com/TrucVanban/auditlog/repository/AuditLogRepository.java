package com.TrucVanban.auditlog.repository;

import com.TrucVanban.auditlog.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("""
            SELECT audit
            FROM AuditLog audit
            WHERE audit.visibilityScope IN :visibilityScopes
              AND (:documentCode IS NULL OR audit.documentCode = :documentCode)
              AND (:transactionCode IS NULL OR audit.transactionCode = :transactionCode)
              AND (:correlationId IS NULL OR audit.correlationId = :correlationId)
              AND (:action IS NULL OR audit.action = :action)
              AND (:result IS NULL OR audit.result = :result)
              AND audit.createdAt >= COALESCE(:fromTime, audit.createdAt)
              AND audit.createdAt <= COALESCE(:toTime, audit.createdAt)
            """)
    Page<AuditLog> search(
            @Param("visibilityScopes") List<String> visibilityScopes,
            @Param("documentCode") String documentCode,
            @Param("transactionCode") String transactionCode,
            @Param("correlationId") String correlationId,
            @Param("action") String action,
            @Param("result") String result,
            @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime,
            Pageable pageable);

    @Query("""
            SELECT audit
            FROM AuditLog audit
            WHERE audit.visibilityScope IN :visibilityScopes
              AND (
                    audit.documentCode = :documentCode
                    OR EXISTS (
                        SELECT related.id
                        FROM AuditLog related
                        WHERE related.documentCode = :documentCode
                          AND related.correlationId IS NOT NULL
                          AND related.correlationId = audit.correlationId
                    )
                  )
            """)
    Page<AuditLog> findDocumentTimeline(
            @Param("documentCode") String documentCode,
            @Param("visibilityScopes") List<String> visibilityScopes,
            Pageable pageable);

    Page<AuditLog> findByVisibilityScopeIn(List<String> visibilityScopes, Pageable pageable);
}

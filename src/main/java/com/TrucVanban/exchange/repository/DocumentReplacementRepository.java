package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.DocumentReplacement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentReplacementRepository extends JpaRepository<DocumentReplacement, Long> {
    @Query("SELECT r FROM DocumentReplacement r WHERE r.replacementDocumentId = :id OR r.replacedDocumentId = :id")
    List<DocumentReplacement> findAllRelatedByDocumentId(@Param("id") Long id);
}
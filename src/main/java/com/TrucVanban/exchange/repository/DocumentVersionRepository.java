package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);
    List<DocumentVersion> findAllByDocumentIdOrderByVersionNoAsc(Long documentId);
}
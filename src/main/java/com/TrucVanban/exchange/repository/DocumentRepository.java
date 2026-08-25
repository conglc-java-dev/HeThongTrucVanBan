package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    boolean existsDocumentByDocumentCode(String documentCode);
    Optional<Document> findByDocumentCode(String documentCode);
}
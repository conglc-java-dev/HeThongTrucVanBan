package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.DocumentSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, Long> {

    /**
     * Lấy toàn bộ nét ký của một giao dịch, sắp xếp theo thứ tự ký.
     */
    List<DocumentSignature> findByTransactionIdOrderBySignatureOrderAsc(Long transactionId);
    long countByTransactionId(Long transactionId);
}

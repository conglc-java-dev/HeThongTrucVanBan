package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.ExchangeTransactions;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeTransactionsRepository extends JpaRepository<ExchangeTransactions, Long> {
    Optional<ExchangeTransactions> findByTransactionCode(String transactionCode);
}

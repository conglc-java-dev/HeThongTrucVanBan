package com.TrucVanban.exchange.repository;

import com.TrucVanban.exchange.entity.ExchangeTransactions;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeTransactionsRepository extends JpaRepository<ExchangeTransactions, Long> {
}
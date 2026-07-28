package com.TrucVanban.shared.outbox.repository;

import com.TrucVanban.shared.outbox.entity.OutboxEvent;
import com.TrucVanban.shared.outbox.enums.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status);
}

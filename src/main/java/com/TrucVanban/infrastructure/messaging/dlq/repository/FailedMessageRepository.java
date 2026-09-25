package com.TrucVanban.infrastructure.messaging.dlq.repository;

import com.TrucVanban.infrastructure.messaging.dlq.entity.FailedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailedMessageRepository extends JpaRepository<FailedMessage, Long> {

    List<FailedMessage> findAllByOrderByFailedAtDesc();
}

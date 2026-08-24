package com.TrucVanban.shared.dlq.repository;

import com.TrucVanban.shared.dlq.entity.FailedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailedMessageRepository extends JpaRepository<FailedMessage, Long> {

    List<FailedMessage> findAllByOrderByFailedAtDesc();
}

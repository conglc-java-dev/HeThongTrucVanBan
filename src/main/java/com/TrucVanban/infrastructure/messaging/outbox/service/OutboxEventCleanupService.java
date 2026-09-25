package com.TrucVanban.infrastructure.messaging.outbox.service;

public interface OutboxEventCleanupService {
    void cleanupProcessedEvents();
}

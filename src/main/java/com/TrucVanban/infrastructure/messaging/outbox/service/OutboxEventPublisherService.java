package com.TrucVanban.infrastructure.messaging.outbox.service;

public interface OutboxEventPublisherService {
    void publishPendingEvents();
}

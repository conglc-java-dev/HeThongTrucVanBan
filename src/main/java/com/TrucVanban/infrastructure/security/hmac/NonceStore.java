package com.TrucVanban.infrastructure.security.hmac;

import java.time.Duration;

public interface NonceStore {
    boolean reserveNonce(String keyId, String nonce, Duration ttl);
}

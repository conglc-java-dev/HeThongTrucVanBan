package com.TrucVanban.registry;

import com.TrucVanban.BaseIT;
import com.TrucVanban.registry.entity.ApiKey;
import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.ApiKeyStatus;
import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.registry.repository.ApiKeyRepository;
import com.TrucVanban.registry.repository.OrganizationRepository;
import com.TrucVanban.shared.security.hmac.AesGcmEncryptionService;
import com.TrucVanban.shared.security.hmac.ApiKeyCacheService;
import com.TrucVanban.shared.security.hmac.ApiKeyCacheValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-03: Kiểm thử ApiKeyCacheService — sự phối hợp giữa Redis cache và PostgreSQL.
 *
 * <p>Đây là tầng bảo mật HMAC cốt lõi. Các kịch bản test xác minh:
 * - Cache Miss: Load từ DB, sau đó populate Redis.
 * - Eviction: Xóa đúng tất cả key Redis của 1 cơ quan.
 * - Negative Cache: API key hết hạn → đánh dấu miss trong Redis, không cho retry DB liên tục.
 *
 * <p>Gọi trực tiếp Service (không qua HTTP) vì đây là unit tích hợp của tầng cache.
 * Dùng @Transactional + repository để seed/cleanup data thoải mái.
 */
@DisplayName("IT-03: ApiKeyCacheService — Redis + PostgreSQL phối hợp")
class ApiKeyCacheIT extends BaseIT {

    @Autowired
    private ApiKeyCacheService apiKeyCacheService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AesGcmEncryptionService aesGcmEncryptionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // ---- Test fixtures ----
    private static final String KEY_ID = "IT_CACHE_KEY_001";
    private static final String RAW_SECRET = "my-it-test-secret-key";
    private Organization testOrg;
    private ApiKey testApiKey;

    @BeforeEach
    void setUp() {
        // Dọn Redis
        cleanRedis();

        // Dọn DB data cũ nếu có (chạy ngoài @Transactional nên phải xóa thủ công)
        apiKeyRepository.findByKeyId(KEY_ID).ifPresent(apiKeyRepository::delete);
        organizationRepository.findAll().stream()
                .filter(o -> "IT_CACHE_ORG".equals(o.getCode()))
                .findFirst()
                .ifPresent(o -> {
                    apiKeyRepository.findAll().stream()
                            .filter(k -> o.getId().equals(k.getAgencyId()))
                            .forEach(apiKeyRepository::delete);
                    organizationRepository.delete(o);
                });

        // Seed: tạo organization ACTIVE
        testOrg = organizationRepository.save(Organization.builder()
                .code("IT_CACHE_ORG")
                .name("Org for Cache IT")
                .receiveEndpoint("https://cache-org.example.com/receive")
                .status(OrganizationStatus.ACTIVE)
                .build());

        // Seed: tạo API key ACTIVE trong DB
        testApiKey = apiKeyRepository.save(ApiKey.builder()
                .keyId(KEY_ID)
                .secretEnc(aesGcmEncryptionService.encrypt(RAW_SECRET))
                .secretHint(RAW_SECRET.substring(0, Math.min(4, RAW_SECRET.length())))
                .algorithm("HmacSHA256")
                .agencyId(testOrg.getId())
                .status(ApiKeyStatus.ACTIVE)
                .expiresAt(OffsetDateTime.now().plusYears(1))
                .build());
    }

    private void cleanRedis() {
        var cacheKeys = redisTemplate.keys("apikey:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) redisTemplate.delete(cacheKeys);
        var missKeys = redisTemplate.keys("apikey:miss:*");
        if (missKeys != null && !missKeys.isEmpty()) redisTemplate.delete(missKeys);
        var agencyKeys = redisTemplate.keys("agency:keys:*");
        if (agencyKeys != null && !agencyKeys.isEmpty()) redisTemplate.delete(agencyKeys);
    }

    @Test
    @DisplayName("Cache Miss → load từ DB, populate Redis với đúng agencyId và orgCode")
    void getApiKey_cacheMiss_shouldLoadFromDbAndCacheInRedis() {
        // GIVEN: Redis không có key (đã clean trong @BeforeEach)
        assertThat(redisTemplate.hasKey("apikey:" + KEY_ID)).isFalse();

        // WHEN: gọi getApiKey lần đầu (cache miss)
        ApiKeyCacheValue result = apiKeyCacheService.getApiKey(KEY_ID);

        // THEN — trả về đúng dữ liệu từ DB
        assertThat(result).as("Phải trả về ApiKeyCacheValue hợp lệ").isNotNull();
        assertThat(result.keyId()).isEqualTo(KEY_ID);
        assertThat(result.agencyCode()).isEqualTo("IT_CACHE_ORG");
        assertThat(result.agencyId()).isEqualTo(testOrg.getId());

        // THEN — Redis được populate sau cache miss
        Boolean cachedInRedis = redisTemplate.hasKey("apikey:" + KEY_ID);
        assertThat(cachedInRedis)
                .as("Redis phải được populate với key 'apikey:%s' sau cache miss", KEY_ID)
                .isTrue();
    }

    @Test
    @DisplayName("Cache Hit → gọi lần 2 vẫn trả về đúng giá trị từ Redis")
    void getApiKey_cacheHit_shouldReturnCachedValue() {
        // GIVEN: gọi lần 1 để populate cache
        apiKeyCacheService.getApiKey(KEY_ID);
        assertThat(redisTemplate.hasKey("apikey:" + KEY_ID)).isTrue();

        // WHEN: gọi lần 2 (cache hit)
        ApiKeyCacheValue result = apiKeyCacheService.getApiKey(KEY_ID);

        // THEN — kết quả vẫn chính xác
        assertThat(result).isNotNull();
        assertThat(result.keyId()).isEqualTo(KEY_ID);
        assertThat(result.agencyStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("API key hết hạn → trả về null, Redis được set negative cache marker")
    void getApiKey_expiredKey_shouldReturnNullAndSetNegativeCache() {
        // GIVEN: update API key trong DB thành đã hết hạn
        testApiKey.setExpiresAt(OffsetDateTime.now().minusDays(1));
        apiKeyRepository.save(testApiKey);

        // WHEN
        ApiKeyCacheValue result = apiKeyCacheService.getApiKey(KEY_ID);

        // THEN — null vì hết hạn
        assertThat(result)
                .as("Phải trả về null khi API key đã hết hạn")
                .isNull();

        // THEN — negative cache marker được set để tránh query DB liên tục
        Boolean negCacheExists = redisTemplate.hasKey("apikey:miss:" + KEY_ID);
        assertThat(negCacheExists)
                .as("Phải set negative cache marker 'apikey:miss:%s'", KEY_ID)
                .isTrue();
    }

    @Test
    @DisplayName("evictAgencyCache → xóa tất cả Redis key của cơ quan đó")
    void evictAgencyCache_shouldDeleteAllRedisKeysForAgency() {
        // GIVEN: populate cache cho KEY_ID qua getApiKey
        apiKeyCacheService.getApiKey(KEY_ID);
        assertThat(redisTemplate.hasKey("apikey:" + KEY_ID)).isTrue();
        assertThat(redisTemplate.hasKey("agency:keys:" + testOrg.getId())).isTrue();

        // WHEN: evict cache của toàn bộ cơ quan
        apiKeyCacheService.evictAgencyCache(testOrg.getId());

        // THEN — tất cả key của cơ quan đã bị xóa khỏi Redis
        assertThat(redisTemplate.hasKey("apikey:" + KEY_ID))
                .as("'apikey:%s' phải bị xóa sau evict", KEY_ID)
                .isFalse();
        assertThat(redisTemplate.hasKey("agency:keys:" + testOrg.getId()))
                .as("'agency:keys:%s' phải bị xóa sau evict", testOrg.getId())
                .isFalse();
    }
}

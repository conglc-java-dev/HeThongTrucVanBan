package com.TrucVanban.shared.auth.service.impl;

import com.TrucVanban.shared.auth.entity.SystemConfig;
import com.TrucVanban.shared.auth.repository.SystemConfigRepository;
import com.TrucVanban.shared.auth.service.SystemConfigService;
import com.TrucVanban.shared.exception.BusinessLogicException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final String INBOUND_CACHE_KEY = "system-config:inbound";
    private static final String OUTBOUND_CACHE_KEY = "system-config:outbound";

    private final SystemConfigRepository systemConfigRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    @Override
    public void loadCache() {
        List<SystemConfig> configs = systemConfigRepository.findAll();

        stringRedisTemplate.delete(INBOUND_CACHE_KEY);
        stringRedisTemplate.delete(OUTBOUND_CACHE_KEY);

        for (SystemConfig config : configs) {
            stringRedisTemplate.opsForHash().put(INBOUND_CACHE_KEY, config.getInboundApiKey(), config.getSystemCode());
            stringRedisTemplate.opsForHash().put(OUTBOUND_CACHE_KEY, config.getSystemCode(), config.getOutboundApiKey());
        }

        log.info("[partner-auth] Loaded {} partner system configs into Redis", configs.size());
    }

    @Override
    public boolean isValidInboundAccess(String systemCode, String apiKey) {
        if (!StringUtils.hasText(systemCode) || !StringUtils.hasText(apiKey)) {
            return false;
        }

        Object cachedSystemCode = stringRedisTemplate.opsForHash().get(INBOUND_CACHE_KEY, apiKey);
        return systemCode.equals(cachedSystemCode);
    }

    @Override
    public String getOutboundApiKey(String systemCode) {
        if (!StringUtils.hasText(systemCode)) {
            throw new BusinessLogicException("System code không hợp lệ");
        }

        Object outboundApiKey = stringRedisTemplate.opsForHash().get(OUTBOUND_CACHE_KEY, systemCode);
        if (outboundApiKey == null || !StringUtils.hasText(outboundApiKey.toString())) {
            throw new BusinessLogicException("Không tìm thấy outbound API Key cho hệ thống: " + systemCode);
        }

        return outboundApiKey.toString();
    }
}

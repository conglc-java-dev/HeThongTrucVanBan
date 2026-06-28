package com.TrucVanban.shared.auth.service;

public interface SystemConfigService {

    void loadCache();

    boolean isValidInboundAccess(String systemCode, String apiKey);

    String getOutboundApiKey(String systemCode);
}

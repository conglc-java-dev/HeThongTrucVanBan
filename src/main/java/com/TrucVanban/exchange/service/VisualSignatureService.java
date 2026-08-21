package com.TrucVanban.exchange.service;

import com.TrucVanban.exchange.dto.request.send.VisualSignatureRequest;

public interface VisualSignatureService {

    String applyVisualLayers(String storagePath, String signerCode,
                             VisualSignatureRequest stampCoords,
                             VisualSignatureRequest signatureCoords) throws Exception;
}

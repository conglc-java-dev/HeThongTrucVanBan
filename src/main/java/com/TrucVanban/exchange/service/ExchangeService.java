package com.TrucVanban.exchange.service;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.SenderDocumentResponse;

import java.util.List;

public interface ExchangeService {
    List<SenderDocumentResponse> exchangeDocument(ExchangeDocumentRequest request);
}

package com.TrucVanban.exchange.service;

import com.TrucVanban.exchange.dto.request.action.InitRecallActionRequest;
import com.TrucVanban.exchange.dto.request.action.InitUpdateActionRequest;
import com.TrucVanban.exchange.dto.response.DocumentActionResponse;

public interface DocumentActionService {
    DocumentActionResponse initRecallAction(InitRecallActionRequest request);
    DocumentActionResponse initUpdateAction(InitUpdateActionRequest request);
}
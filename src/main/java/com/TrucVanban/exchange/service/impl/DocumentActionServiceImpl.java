package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.action.InitRecallActionRequest;
import com.TrucVanban.exchange.dto.request.action.InitUpdateActionRequest;
import com.TrucVanban.exchange.dto.response.DocumentActionResponse;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.DocumentVersion;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.DocumentVersionRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.exchange.service.AuditLogService;
import com.TrucVanban.exchange.service.DocumentActionService;
import com.TrucVanban.registry.service.RegistryService;
import com.TrucVanban.shared.exception.ForbiddenException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DocumentActionServiceImpl implements DocumentActionService {

    DocumentRepository documentRepository;
    ExchangeTransactionsRepository exchangeTransactionsRepository;
    DocumentVersionRepository documentVersionRepository;
    RegistryService registryService;
    AuditLogService auditLogService;

    @Override
    @Transactional
    public DocumentActionResponse initRecallAction(InitRecallActionRequest request) {
        log.info("[initRecallAction] Khởi tạo chiến dịch thu hồi: recalledDoc={}, actionDoc={}, requester={}",
                request.getRecalledDocumentCode(), request.getActionDocumentCode(), request.getRequestedByCode());

        Document recalledDoc = documentRepository.findByDocumentCode(request.getRecalledDocumentCode())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản cần thu hồi: " + request.getRecalledDocumentCode()));

        Long requesterId = registryService.getOrganizationIdByCode(request.getRequestedByCode());
        if (!requesterId.equals(recalledDoc.getSenderOrgId())) {
            throw new ForbiddenException("Chỉ cơ quan phát hành mới được phép khởi tạo chiến dịch thu hồi");
        }

        List<ExchangeTransactions> transactions = exchangeTransactionsRepository.findByDocumentId(recalledDoc.getId());
        int receiversCount = transactions.size();

        Long actionId = System.currentTimeMillis() % 10000;

        auditLogService.log("RECALL_CAMPAIGN_INITIATED", "ORGANIZATION", request.getRequestedByCode(), "SUCCESS",
                String.format("{\"actionId\":%d,\"recalledDoc\":\"%s\",\"actionDoc\":\"%s\",\"reason\":\"%s\"}",
                        actionId, request.getRecalledDocumentCode(), request.getActionDocumentCode(), request.getReason()),
                null, recalledDoc.getId());

        return DocumentActionResponse.builder()
                .actionId(actionId)
                .actionStatus("PENDING")
                .totalReceiversNotified(receiversCount > 0 ? receiversCount : 1)
                .build();
    }

    @Override
    @Transactional
    public DocumentActionResponse initUpdateAction(InitUpdateActionRequest request) {
        log.info("[initUpdateAction] Khởi tạo lệnh cập nhật văn bản: targetDoc={}, requester={}",
                request.getTargetDocumentCode(), request.getRequestedByCode());

        Document targetDoc = documentRepository.findByDocumentCode(request.getTargetDocumentCode())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy văn bản mục tiêu: " + request.getTargetDocumentCode()));

        Long requesterId = registryService.getOrganizationIdByCode(request.getRequestedByCode());
        if (!requesterId.equals(targetDoc.getSenderOrgId())) {
            throw new ForbiddenException("Chỉ cơ quan phát hành mới được phép phát lệnh cập nhật văn bản");
        }

        if (request.getUpdateData() != null && request.getUpdateData().getNewPayload() != null) {
            InitUpdateActionRequest.NewPayload payload = request.getUpdateData().getNewPayload();
            DocumentVersion lastVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(targetDoc.getId()).orElse(null);
            int newVersionNo = lastVersion != null ? lastVersion.getVersionNo() + 1 : 1;

            DocumentVersion newVersion = DocumentVersion.builder()
                    .documentId(targetDoc.getId())
                    .versionNo(newVersionNo)
                    .storagePath(payload.getStoragePath())
                    .checksum(payload.getChecksum())
                    .updateReason(request.getReason())
                    .createdBy(request.getRequestedByCode())
                    .build();
            documentVersionRepository.save(newVersion);
            targetDoc.setCurrentVersion(newVersionNo);
            documentRepository.save(targetDoc);
        }

        List<ExchangeTransactions> transactions = exchangeTransactionsRepository.findByDocumentId(targetDoc.getId());
        int totalNotified = transactions.size();

        if (request.getUpdateData() != null && request.getUpdateData().getAddedReceivers() != null) {
            totalNotified += request.getUpdateData().getAddedReceivers().size();
        }

        Long actionId = System.currentTimeMillis() % 10000;

        auditLogService.log("UPDATE_ACTION_INITIATED", "ORGANIZATION", request.getRequestedByCode(), "SUCCESS",
                String.format("{\"actionId\":%d,\"targetDoc\":\"%s\",\"reason\":\"%s\"}",
                        actionId, request.getTargetDocumentCode(), request.getReason()),
                null, targetDoc.getId());

        return DocumentActionResponse.builder()
                .actionId(actionId)
                .actionStatus("PENDING")
                .totalReceiversNotified(totalNotified > 0 ? totalNotified : 1)
                .build();
    }
}
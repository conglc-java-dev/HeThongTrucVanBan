package com.TrucVanban.exchange.service;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignAndBuildRequest;
import com.TrucVanban.exchange.dto.request.send.SimulateMultiSigRequest;
import com.TrucVanban.exchange.dto.response.FileUploadResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface ClientSimulatorService {

    Object processAndSend(MultipartFile file,
                          String senderCode,
                          List<String> receiverCodes,
                          String documentCode,
                          String certificateSerialNumber,
                          Integer priority,
                          String idempotencyKey) throws Exception;

    List<FileUploadResponse> uploadFiles(MultipartFile[] files) throws Exception;

    ExchangeDocumentRequest signAndBuildPayload(SignAndBuildRequest request) throws Exception;
    MultiSignatureRequest signAndBuildMultiSigPayload(SimulateMultiSigRequest request) throws Exception;

    String getPresignedUrl(String objectKey);
}
package com.TrucVanban.shared.utils;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Chuẩn hóa chuỗi ký (Canonical String Builder) theo quy tắc nghiêm ngặt.
 *
 * <p>Quy tắc chung:</p>
 * <ol>
 *   <li>Tất cả Key viết thường (lowercase)</li>
 *   <li>Sắp xếp Key theo bảng chữ cái A-Z (TreeMap)</li>
 *   <li>Mảng receivers/routingList/distributionList sort tăng dần trước khi nối chuỗi</li>
 *   <li>Mã hóa Value theo URL-Encode chuẩn RFC 3986</li>
 *   <li>Nối mỗi cặp key:value bằng ký tự \n</li>
 * </ol>
 
 */
@Component
public class CanonicalStringBuilder {

    public String build(ExchangeDocumentRequest request) {
        return buildLegacy(
                request.getCertificateSerialNumber(),
                request.getDocumentCode(),
                request.getPayloadChecksum(),
                request.getReceiverCodes(),
                request.getSenderCode(),
                request.getTimestamp()
        );
    }

    public String buildLegacy(
            String certificateSerialNumber,
            String documentCode,
            String payloadChecksum,
            List<String> receivers,
            String senderCode,
            String timestamp
    ) {
        List<String> sortedReceivers = new ArrayList<>();
        if (receivers != null && !receivers.isEmpty()) {
            sortedReceivers.addAll(receivers);
            Collections.sort(sortedReceivers);
        }
        String receiversValue = String.join(",", sortedReceivers);

        TreeMap<String, String> canonicalMap = new TreeMap<>();
        canonicalMap.put("certificate_serial_number", certificateSerialNumber);
        canonicalMap.put("document_code", documentCode);
        canonicalMap.put("payload_checksum", payloadChecksum);
        canonicalMap.put("receivers", receiversValue);
        canonicalMap.put("sender_code", senderCode);
        canonicalMap.put("timestamp", timestamp);

        return buildFromMap(canonicalMap);
    }


    public String build(MultiSignatureRequest request) {
        // Flatten signatures[]: "{order}#{signerCode}#{certificateSerial}", join "|"
        String signaturesFlat = flattenSignatures(request.getSignatures());

        // Sort và join routingList
        String routingListStr = sortAndJoin(request.getRoutingList());

        // Sort và join distributionList
        String distributionListStr = sortAndJoin(request.getDistributionList());

        TreeMap<String, String> canonicalMap = new TreeMap<>();
        canonicalMap.put("current_sender_code", request.getCurrentSenderCode());
        canonicalMap.put("distribution_list", distributionListStr);
        canonicalMap.put("document_code", request.getDocumentCode());
        canonicalMap.put("master_transaction_code", request.getMasterTransactionCode());
        canonicalMap.put("request_timestamp", request.getRequestTimestamp());
        canonicalMap.put("routing_list", routingListStr);
        canonicalMap.put("signatures_flat", signaturesFlat);
        canonicalMap.put("storage_path", request.getStoragePath());

        return buildFromMap(canonicalMap);
    }

    /**
     * Flatten mảng signatures[] thành chuỗi đơn.
     * Format: "{order}#{signerCode}#{certificateSerial}", các phần tử join bằng "|"
     * Ví dụ: "1#A_BGDDT#540453...|2#B_BTC#999953..."
     */
    public String flattenSignatures(List<SignatureRequest> signatures) {
        if (signatures == null || signatures.isEmpty()) return "";
        return signatures.stream()
                .sorted((a, b) -> Integer.compare(
                        a.getSignatureOrder() != null ? a.getSignatureOrder() : 0,
                        b.getSignatureOrder() != null ? b.getSignatureOrder() : 0))
                .map(sig -> sig.getSignatureOrder() + "#"
                        + sig.getSignerCode() + "#"
                        + sig.getCertificateSerialNumber())
                .collect(Collectors.joining("|"));
    }

    // ===== PRIVATE HELPERS =====

    private String buildFromMap(TreeMap<String, String> canonicalMap) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var entry : canonicalMap.entrySet()) {
            if (!first) sb.append('\n');
            sb.append(entry.getKey())
                    .append(':')
                    .append(urlEncode(entry.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private String sortAndJoin(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        List<String> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")     // URLEncoder đổi khoảng trắng thành '+', cần đưa về %20
                .replace("%2B", "%2B")   // Giữ nguyên mã hóa dấu + (múi giờ ISO-8601)
                .replace("*", "%2A")     // Mã hóa dấu * theo RFC 3986
                .replace("%7E", "~");    // Dấu ~ không mã hóa
    }
}
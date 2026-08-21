package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.send.VisualSignatureRequest;
import com.TrucVanban.exchange.service.VisualSignatureService;
import com.TrucVanban.registry.entity.OrganizationVisualAsset;
import com.TrucVanban.registry.enums.AssetType;
import com.TrucVanban.registry.repository.OrganizationVisualAssetRepository;
import com.TrucVanban.shared.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * Triển khai vẽ con dấu và chữ ký trực quan lên file PDF bằng Apache PDFBox.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualSignatureServiceImpl implements VisualSignatureService {

        private final MinioService minioService;
        private final OrganizationVisualAssetRepository visualAssetRepository;

        /** Prefix thư mục MinIO cho file PDF đã vẽ dấu, tránh lẫn với file gốc. */
        private static final String SIGNED_PDF_PREFIX = "signed-pdfs/";

        @Override
        public String applyVisualLayers(String storagePath, String signerCode,
                        VisualSignatureRequest stampCoords,
                        VisualSignatureRequest signatureCoords) throws Exception {

                boolean drawStamp = stampCoords != null && stampCoords.isApplyVisual();
                boolean drawSignature = signatureCoords != null && signatureCoords.isApplyVisual();

                if (!drawStamp && !drawSignature) {
                        log.info("[VisualSig] Không có layer nào được yêu cầu vẽ — trả về storagePath gốc.");
                        return storagePath;
                }

                log.info("[VisualSig] Bắt đầu vẽ layers: signerCode={}, stamp={}, signature={}",
                                signerCode, drawStamp, drawSignature);

                // 1. Tải ảnh từ DB nếu cần
                byte[] stampBytes = drawStamp
                                ? loadImageBytesForAsset(signerCode, AssetType.STAMP_MAIN)
                                : null;
                byte[] signatureBytes = drawSignature
                                ? loadImageBytesForAsset(signerCode, AssetType.SIGNATURE_LEADER)
                                : null;

                // 2. Tải file PDF gốc từ MinIO
                byte[] pdfBytes;
                try (InputStream pdfStream = minioService.download(storagePath)) {
                        pdfBytes = pdfStream.readAllBytes();
                }
                log.info("[VisualSig] Tải PDF gốc: {} bytes", pdfBytes.length);

                // 3. Vẽ cả 2 layer trên cùng 1 PDDocument (mở 1 lần, lưu 1 lần)
                byte[] signedPdfBytes = drawLayersOnPdf(pdfBytes,
                                stampBytes, stampCoords,
                                signatureBytes, signatureCoords);

                // 4. Upload PDF mới lên MinIO
                String newObjectKey = buildNewObjectKey(storagePath);
                minioService.uploadBytes(newObjectKey, signedPdfBytes, "application/pdf");

                log.info("[VisualSig] Hoàn tất. File mới: {}", newObjectKey);
                return newObjectKey;
        }

        // -----------------------------------------------------------------------
        // Private helpers
        // -----------------------------------------------------------------------

        /**
         * Tải byte[] ảnh từ URL trong bảng organization_visual_assets.
         * Hỗ trợ cả URL ngoài (http/https) và MinIO Object Key.
         */
        private byte[] loadImageBytesForAsset(String orgCode, AssetType assetType) throws Exception {
                OrganizationVisualAsset asset = visualAssetRepository
                                .findFirstByOrganizationCodeAndAssetTypeAndIsDefaultTrueAndIsActiveTrue(
                                                orgCode, assetType)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Cơ quan [" + orgCode + "] chưa cấu hình asset loại: "
                                                                + assetType.name()
                                                                + ". Vui lòng thêm vào bảng organization_visual_assets."));

                String imageUrl = asset.getImageUrl();
                log.info("[VisualSig] Tải ảnh {} từ: {}", assetType, imageUrl);

                if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                        try (InputStream stream = java.net.URI.create(imageUrl).toURL().openStream()) {
                                byte[] bytes = stream.readAllBytes();
                                log.info("[VisualSig] Tải từ URL ngoài thành công: {} bytes", bytes.length);
                                return bytes;
                        }
                } else {
                        try (InputStream stream = minioService.download(imageUrl)) {
                                byte[] bytes = stream.readAllBytes();
                                log.info("[VisualSig] Tải từ MinIO thành công: {} bytes", bytes.length);
                                return bytes;
                        }
                }
        }

        /**
         * Vẽ tối đa 2 layer ảnh lên cùng 1 {@code PDDocument}.
         *
         * <p>Thiết kế: mở PDF 1 lần, vẽ tuần tự stamp → signature, lưu 1 lần.
         * Tránh overhead tải/lưu PDF nhiều lần.
         */
        private byte[] drawLayersOnPdf(byte[] pdfBytes,
                        byte[] stampBytes, VisualSignatureRequest stampCoords,
                        byte[] signatureBytes, VisualSignatureRequest signatureCoords) throws Exception {

                try (PDDocument document = Loader.loadPDF(pdfBytes);
                                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                        if (stampBytes != null && stampCoords != null) {
                                PDImageXObject stampImage = PDImageXObject.createFromByteArray(
                                                document, stampBytes, "stamp");
                                drawImageOnPage(document, stampImage, stampCoords, "STAMP");
                        }

                        if (signatureBytes != null && signatureCoords != null) {
                                PDImageXObject sigImage = PDImageXObject.createFromByteArray(
                                                document, signatureBytes, "signature");
                                drawImageOnPage(document, sigImage, signatureCoords, "SIGNATURE");
                        }

                        document.save(outputStream);
                        return outputStream.toByteArray();
                }
        }

        /**
         * Vẽ một ảnh lên trang PDF chỉ định, tính tọa độ từ ratio FE.
         *
         * <p>Quy trình tọa độ:
         * <ol>
         *   <li>Lấy kích thước trang thực (PDF Points = 1/72 inch)</li>
         *   <li>Tính vị trí và kích thước từ ratio × kích thước trang</li>
         *   <li>Flip Y: pdfY = pageH - yRatio*pageH - imgH (Top-Left → Bottom-Left)</li>
         * </ol>
         */
        private void drawImageOnPage(PDDocument document, PDImageXObject image,
                        VisualSignatureRequest coords, String layerName) throws Exception {

                int totalPages = document.getNumberOfPages();
                int targetPageIndex = coords.getPageNumber() - 1; // 0-based

                if (targetPageIndex < 0 || targetPageIndex >= totalPages) {
                        throw new IllegalArgumentException(
                                        "[" + layerName + "] pageNumber=" + coords.getPageNumber()
                                                        + " không hợp lệ. PDF có " + totalPages + " trang.");
                }

                PDPage targetPage = document.getPage(targetPageIndex);
                PDRectangle mediaBox = targetPage.getMediaBox();
                float pageWidth = mediaBox.getWidth();
                float pageHeight = mediaBox.getHeight();

                float imgW = (float) (coords.getWidthRatio() * pageWidth);
                float imgH = (float) (coords.getHeightRatio() * pageHeight);
                float imgX = (float) (coords.getPositionXRatio() * pageWidth);
                // Flip Y: Top-Left (HTML) → Bottom-Left (PDF)
                float imgY = (float) (pageHeight - coords.getPositionYRatio() * pageHeight - imgH);

                log.info("[VisualSig] {} — trang {}: x={:.2f}, y(BL)={:.2f}, w={:.2f}, h={:.2f}",
                                layerName, coords.getPageNumber(),
                                imgX, imgY, imgW, imgH);

                try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, targetPage, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        contentStream.drawImage(image, imgX, imgY, imgW, imgH);
                }
        }

        /**
         * Tạo object key mới cho file PDF đã vẽ dấu.
         * Format: {@code signed-pdfs/{UUID}-{tên-file-gốc}}
         */
        private String buildNewObjectKey(String originalStoragePath) {
                String originalFileName = originalStoragePath.contains("/")
                                ? originalStoragePath.substring(originalStoragePath.lastIndexOf('/') + 1)
                                : originalStoragePath;
                return SIGNED_PDF_PREFIX + UUID.randomUUID() + "-" + originalFileName;
        }
}

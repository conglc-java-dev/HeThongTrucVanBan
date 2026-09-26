package com.TrucVanban.shared.utils;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CanonicalStringBuilderTest {

    private final CanonicalStringBuilder canonicalStringBuilder = new CanonicalStringBuilder();

    @Test
    void buildLegacy_includesIssuedDateWhenProvided() {
        ExchangeDocumentRequest request = new ExchangeDocumentRequest();
        request.setCertificateSerialNumber("CERT-01");
        request.setDocumentCode("DOC-01");
        request.setPayloadChecksum("checksum");
        request.setReceiverCodes(List.of("B_ORG"));
        request.setSenderCode("A_ORG");
        request.setTimestamp("2026-08-25T00:00:00+07:00");
        request.setIssuedDate("2026-08-25");

        String canonical = canonicalStringBuilder.build(request);

        assertTrue(canonical.contains("issued_date:2026-08-25"));
    }

    @Test
    void buildMultiSignature_includesIssuedDateWhenProvided() {
        MultiSignatureRequest request = new MultiSignatureRequest();
        request.setCurrentSenderCode("A_ORG");
        request.setDocumentCode("DOC-01");
        request.setMasterTransactionCode("MASTER-01");
        request.setRequestTimestamp("2026-08-25T00:00:00+07:00");
        request.setStoragePath("documents/DOC-01.pdf");
        request.setIssuedDate("2026-08-25T00:00:00+07:00");

        String canonical = canonicalStringBuilder.build(request);

        assertTrue(canonical.contains("issued_date:2026-08-25T00%3A00%3A00%2B07%3A00"));
    }

    @Test
    void issuedDate_acceptsDdMMyyyyFormat() {
        ExchangeDocumentRequest request = new ExchangeDocumentRequest();
        request.setIssuedDate("25-08-2026");
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        boolean hasIssuedDateViolation = validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("issuedDate"));

        assertFalse(hasIssuedDateViolation);
    }
}

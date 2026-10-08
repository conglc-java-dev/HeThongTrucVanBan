package com.TrucVanban.exchange.service.impl;

import com.TrucVanban.exchange.dto.request.send.SignAndBuildRequest;
import com.TrucVanban.shared.utils.CanonicalStringBuilder;
import com.TrucVanban.shared.utils.SignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSimulatorServiceImplTest {

    @Test
    void signAndBuildPayload_usesPrivateKeyOfSender() throws Exception {
        CanonicalStringBuilder canonicalStringBuilder = new CanonicalStringBuilder();
        ClientSimulatorServiceImpl service = new ClientSimulatorServiceImpl(
                null, canonicalStringBuilder, null, null);
        SignAndBuildRequest request = new SignAndBuildRequest();
        request.setSenderCode("A_BGDDT");
        request.setReceiverCodes(List.of("B_BTC"));
        request.setDocumentCode("DOC-001");
        request.setCertificateSerialNumber("540453303831353930323330");
        request.setStoragePath("documents/DOC-001.pdf");
        request.setPayloadChecksum("checksum");
        request.setIssuedDate("2026-08-25");

        var payload = service.signAndBuildPayload(request);

        assertTrue(SignatureVerifier.verify(
                canonicalStringBuilder.build(payload),
                payload.getSignature(),
                publicKeyPemFor("A_BGDDT")));
    }

    private String publicKeyPemFor(String senderCode) throws Exception {
        ClassPathResource resource = new ClassPathResource("keys/" + senderCode + "_private_key.pem");
        String keyContent;
        try (var inputStream = resource.getInputStream()) {
            keyContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        String normalizedKey = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalizedKey)));
        RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent()));

        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}

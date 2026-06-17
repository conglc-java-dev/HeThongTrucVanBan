//package com.TrucVanban.exchange.entity;
//
//import com.TrucVanban.exchange.dto.command.DocumentReceiverCreateCommand;
//import com.TrucVanban.exchange.utils.NumberUtils;
//import com.TrucVanban.exchange.utils.StringUtils;
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.NoArgsConstructor;
//
//import java.time.LocalDateTime;
//
//@Entity
//@Table(name = "document_receivers")
//@NoArgsConstructor
//@AllArgsConstructor
//@Builder
//public class DocumentReceiver {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(name = "document_id", nullable = false)
//    private Long documentId;
//
//    @Column(name = "receiver_org_id", nullable = false)
//    private Long receiverOrgId;
//
//    @Column(name = "business_status_code", nullable = false, length = 2)
//    private String businessStatusCode;
//
//    @Column(name = "status_reason", columnDefinition = "text")
//    private String statusReason;
//
//    @Column(name = "received_at")
//    private LocalDateTime receivedAt;
//
//    @Column(name = "processed_at")
//    private LocalDateTime processedAt;
//
//    @Version
//    @Builder.Default
//    @Column(name = "version")
//    private Integer version = 0;
//
//    public static DocumentReceiver of(DocumentReceiverCreateCommand command) {
//        if(command == null) throw new IllegalArgumentException("Command must not be null");
//        if(NumberUtils.isNullOrNegative(command.getDocumentId())) throw new IllegalArgumentException("Document id must be a positive number");
//        if(NumberUtils.isNullOrNegative(command.getReceiverOrgId())) throw new IllegalArgumentException("Receiver org id must be a positive number");
//        if(StringUtils.isNullOrBlank(command.getBusinessStatusCode())) throw new IllegalArgumentException("Business status code must not be null or blank");
//
//        return DocumentReceiver.builder()
//                .documentId(command.getDocumentId())
//                .receiverOrgId(command.getReceiverOrgId())
//                .businessStatusCode(command.getBusinessStatusCode())
//                .statusReason(command.getStatusReason())
//                .build();
//    }
//}

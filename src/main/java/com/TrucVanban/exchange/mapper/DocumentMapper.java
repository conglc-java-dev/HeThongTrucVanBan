package com.TrucVanban.exchange.mapper;

import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.entity.Document;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DocumentMapper {

    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "currentVersion", constant = "1")
    @Mapping(source = "documentCode", target = "documentCode")
    @Mapping(source = "title", target = "title")
    @Mapping(source = "documentType", target = "documentType")
    @Mapping(source = "extractedMetadata", target = "extractedMetadata")
    @Mapping(source = "summary", target = "summary")
    Document toDocument(ExchangeDocumentRequest request);
}

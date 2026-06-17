package com.TrucVanban.exchange.mapper;

import com.TrucVanban.exchange.dto.command.DocumentCreateCommand;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DocumentMapper {

    @Mapping(source = "documentCode", target = "documentCode")
    @Mapping(source = "title", target = "title")
    @Mapping(source = "documentType", target = "documentType")
    @Mapping(source = "extractedMetadata", target = "extractedMetadata")
    @Mapping(source = "summary", target = "summary")
    @Mapping(target = "senderOrgId", ignore = true)
    @Mapping(target = "currentVersion", ignore = true)
    @Mapping(target = "status", ignore = true)
    DocumentCreateCommand toDocumentCreateCommand(ExchangeDocumentRequest request);
}

package com.TrucVanban.auth.mapper;

import com.TrucVanban.auth.dto.request.RoleRequest;
import com.TrucVanban.auth.dto.response.RoleDetailResponse;
import com.TrucVanban.auth.dto.response.RoleResponse;
import com.TrucVanban.auth.entity.Role;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface RoleMapper {
    
    @Mapping(target = "id", ignore = true)

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Role toEntity(RoleRequest request);

    @Mapping(target = "permissions", ignore = true)
    RoleResponse toResponse(Role role);

    @Mapping(target = "permissions", ignore = true)
    RoleDetailResponse toDetailResponse(Role role);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(RoleRequest request, @MappingTarget Role role);
}

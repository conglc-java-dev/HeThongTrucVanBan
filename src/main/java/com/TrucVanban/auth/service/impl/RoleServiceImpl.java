package com.TrucVanban.auth.service.impl;

import com.TrucVanban.auth.dto.request.RoleRequest;
import com.TrucVanban.auth.dto.response.RoleDetailResponse;
import com.TrucVanban.auth.dto.response.RoleResponse;
import com.TrucVanban.auth.entity.Permission;
import com.TrucVanban.auth.entity.Role;
import com.TrucVanban.auth.entity.RolePermission;
import com.TrucVanban.auth.mapper.RoleMapper;
import com.TrucVanban.auth.repository.PermissionRepository;
import com.TrucVanban.auth.repository.RolePermissionRepository;
import com.TrucVanban.auth.repository.RoleRepository;
import com.TrucVanban.auth.repository.UserRoleRepository;
import com.TrucVanban.auth.service.RoleService;
import com.TrucVanban.shared.exception.BusinessLogicException;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleMapper roleMapper;

    @Override
    @Transactional
    public RoleResponse createRole(RoleRequest request) {
        if (roleRepository.findByCode(request.getCode()).isPresent()) {
            throw new DuplicateResourceException("Mã vai trò đã tồn tại");
        }
        Role role = roleMapper.toEntity(request);
        Role savedRole = roleRepository.save(role);

        if (request.getPermissions() != null) {
            for (String code : request.getPermissions()) {
                permissionRepository.findByCode(code).ifPresent(permission -> {
                    rolePermissionRepository.save(RolePermission.builder().roleId(savedRole.getId()).permissionId(permission.getId()).build());
                });
            }
        }
        
        return mapToResponseWithPermissions(savedRole);
    }

    @Override
    @Transactional
    public RoleResponse updateRole(Long id, RoleRequest request) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vai trò"));
        
        if (request.getCode() != null && !request.getCode().equals(role.getCode())) {
            if (roleRepository.findByCode(request.getCode()).isPresent()) {
                throw new DuplicateResourceException("Mã vai trò đã tồn tại");
            }
        }

        roleMapper.updateEntityFromRequest(request, role);
        Role savedRole = roleRepository.save(role);

        if (request.getPermissions() != null) {
            rolePermissionRepository.deleteByRoleId(savedRole.getId());
            for (String code : request.getPermissions()) {
                permissionRepository.findByCode(code).ifPresent(permission -> {
                    rolePermissionRepository.save(RolePermission.builder().roleId(savedRole.getId()).permissionId(permission.getId()).build());
                });
            }
        }
        
        return mapToResponseWithPermissions(savedRole);
    }

    @Override
    @Transactional(readOnly = true)
    public RoleDetailResponse getRoleById(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vai trò"));
                
        RoleDetailResponse response = roleMapper.toDetailResponse(role);

        List<Long> permissionIds = rolePermissionRepository.findByRoleId(role.getId()).stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toList());
        Set<String> rolePermissionCodes = permissionRepository.findAllById(permissionIds).stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        List<Permission> allPermissions = permissionRepository.findAll();
        List<RoleDetailResponse.PermissionInfo> permissionInfos = allPermissions.stream().map(p -> {
            RoleDetailResponse.PermissionInfo info = new RoleDetailResponse.PermissionInfo();
            info.setCode(p.getCode());
            info.setName(p.getName());
            info.setDescription(p.getDescription());
            info.setHasPermission(rolePermissionCodes.contains(p.getCode()));
            return info;
        }).collect(Collectors.toList());

        response.setPermissions(permissionInfos);
        
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::mapToResponseWithPermissions)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vai trò"));
        
        if (userRoleRepository.existsByRoleId(id)) {
            throw new BusinessLogicException("Không thể xóa vai trò đang được gán cho người dùng");
        }

        rolePermissionRepository.deleteByRoleId(id);
        roleRepository.delete(role);
    }
    
    private RoleResponse mapToResponseWithPermissions(Role role) {
        RoleResponse response = roleMapper.toResponse(role);
        List<Long> permissionIds = rolePermissionRepository.findByRoleId(role.getId()).stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toList());
        Set<String> permissions = permissionRepository.findAllById(permissionIds).stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        response.setPermissions(permissions);
        return response;
    }
}

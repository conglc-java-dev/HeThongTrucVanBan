package com.TrucVanban.auth.repository;

import com.TrucVanban.auth.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import com.TrucVanban.auth.entity.RolePermissionId;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    List<RolePermission> findByRoleId(Long roleId);
    void deleteByRoleId(Long roleId);
}

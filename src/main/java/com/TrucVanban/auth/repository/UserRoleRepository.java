package com.TrucVanban.auth.repository;

import com.TrucVanban.auth.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import com.TrucVanban.auth.entity.UserRoleId;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(Long userId);
    void deleteByUserId(Long userId);
    boolean existsByRoleId(Long roleId);
}

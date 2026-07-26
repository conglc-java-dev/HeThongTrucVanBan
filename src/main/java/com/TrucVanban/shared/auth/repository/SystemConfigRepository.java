package com.TrucVanban.shared.auth.repository;

import com.TrucVanban.shared.auth.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {
}

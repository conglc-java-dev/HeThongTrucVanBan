package com.TrucVanban.registry.repository;

import com.TrucVanban.registry.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;


public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findByCode(String code);

    boolean existsByCode(String code);
}

package com.TrucVanban.registry.repository;

import com.TrucVanban.registry.entity.Organization;
import com.TrucVanban.registry.enums.OrganizationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findByCode(String code);

    List<Organization> findByCodeIn(List<String> codes);

    boolean existsByCode(String code);

    /**
     * Tra cứu cơ quan với filter tùy chọn:
     * - status: null = lấy tất cả trạng thái
     * - search: null = bỏ qua, có giá trị = tìm LIKE không phân biệt hoa thường theo name hoặc code
     */
    @Query("""
            SELECT o FROM Organization o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:search IS NULL OR LOWER(o.name) LIKE LOWER(CONCAT('%', :search, '%'))
                                   OR LOWER(o.code) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY o.name ASC
            """)
    Page<Organization> findByFilters(
            @Param("status") OrganizationStatus status,
            @Param("search") String search,
            Pageable pageable);
}

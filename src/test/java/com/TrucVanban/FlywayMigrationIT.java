package com.TrucVanban;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-01: Xác minh toàn bộ Flyway migration schema chạy thành công trên PostgreSQL thật.
 *
 * <p>Đây là bài test NỀN TẢNG quan trọng nhất. Nếu bất kỳ migration nào trong
 * V1__init_schema.sql đến V15__add_issued_date_to_documents.sql có lỗi cú pháp
 * hoặc xung đột kiểu dữ liệu, bài test này sẽ fail TRƯỚC KHI bất kỳ IT nào khác chạy,
 * tránh lãng phí thời gian debug những lỗi gây ra bởi schema không hợp lệ.
 */
@DisplayName("IT-01: Flyway Schema Migration")
class FlywayMigrationIT extends BaseIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Danh sách bảng cốt lõi cần tồn tại sau khi migrate V1 đến V15
    private static final List<String> EXPECTED_TABLES = List.of(
            "organizations",
            "certificates",
            "sla_configurations",
            "documents",
            "document_versions",
            "exchange_transactions",
            "status_history",
            "outbox_event",
            "failed_messages",
            "api_keys",
            "users",
            "roles",
            "refresh_tokens"
    );

    /**
     * Kiểm tra tất cả migration từ V1 đến V15 đều được apply thành công
     * (không có migration nào ở trạng thái FAILED).
     */
    @Test
    @DisplayName("Tất cả migration V1→V15 phải apply thành công, không có migration nào FAILED")
    void allMigrationsApplySuccessfully() {
        var appliedMigrations = flyway.info().applied();

        // Phải có ít nhất 15 migration đã được apply
        assertThat(appliedMigrations)
                .as("Phải có ít nhất 15 migration được apply")
                .hasSizeGreaterThanOrEqualTo(15);

        // Không có migration nào ở trạng thái FAILED
        var failedMigrations = java.util.Arrays.stream(appliedMigrations)
                .filter(info -> info.getState().isFailed())
                .toList();
        assertThat(failedMigrations)
                .as("Không được có migration nào bị FAILED: %s", failedMigrations)
                .isEmpty();
    }

    /**
     * Kiểm tra tất cả bảng nghiệp vụ cốt lõi tồn tại sau khi migrate.
     * Query trực tiếp vào information_schema của PostgreSQL thật.
     */
    @Test
    @DisplayName("Tất cả bảng nghiệp vụ cốt lõi phải tồn tại sau migration")
    void allExpectedTablesExist() {
        for (String tableName : EXPECTED_TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class,
                    tableName
            );
            assertThat(count)
                    .as("Bảng '%s' phải tồn tại trong schema public", tableName)
                    .isEqualTo(1);
        }
    }

    /**
     * Kiểm tra version migration cuối cùng đúng là V15 và trạng thái là SUCCESS.
     * Bảo vệ chống lại việc ai đó tạo migration mới mà quên update test này.
     */
    @Test
    @DisplayName("Version migration cuối cùng phải là V15 với trạng thái SUCCESS")
    void latestMigrationVersionIsV15() {
        String latestVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history " +
                "WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class
        );
        assertThat(latestVersion)
                .as("Version migration mới nhất được apply thành công phải là 15")
                .isEqualTo("15");
    }
}

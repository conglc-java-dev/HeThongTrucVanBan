package com.TrucVanban;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class cho toàn bộ Integration Test.
 *
 * <p><b>Singleton Container Pattern:</b> 3 container (Postgres, Redis, RabbitMQ) chỉ được
 * khởi động DUY NHẤT MỘT LẦN cho toàn bộ test suite nhờ khai báo {@code static}.
 * JVM giữ chúng sống trong suốt quá trình chạy IT, Ryuk container sẽ hủy sạch khi JVM kết thúc.
 *
 * <p><b>Lý do dùng {@code @SpringBootTest} với {@code RANDOM_PORT}:</b>
 * Tránh xung đột cổng giữa các lần chạy test song song. Port thật được inject qua
 * {@code @LocalServerPort} trong từng subclass khi cần.
 *
 * <p><b>Lý do dùng {@code @ActiveProfiles("it")}:</b>
 * Load {@code application-it.yml} để disable scheduler, HMAC filter, reduce SQL noise.
 * Datasource/Redis/RabbitMQ connection string bị ghi đè bởi {@link #overrideProperties}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
public abstract class BaseIT {

    // =====================================================================
    // PostgreSQL Container
    // static final → chỉ tạo 1 lần, tái dùng cho tất cả subclass
    // =====================================================================
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("trucvanban_it")
                    .withUsername("it_user")
                    .withPassword("it_pass");

    // =====================================================================
    // Redis Container (dùng GenericContainer vì Testcontainers không có
    // module riêng cho Redis; redis:7-alpine = image nhỏ nhất)
    // =====================================================================
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    // =====================================================================
    // RabbitMQ Container (rabbitmq:3.13-management-alpine chứa UI + CLI)
    // =====================================================================
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    // Khởi động tất cả container 1 lần trước khi bất kỳ test nào trong suite chạy
    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
    }

    /**
     * Nạp connection string động vào Spring Context.
     *
     * <p>Spring gọi method này SAU KHI container đã start (port ngẫu nhiên đã được gán),
     * TRƯỚC KHI ApplicationContext được tạo. Nhờ đó Spring Boot kết nối đúng đến
     * container Testcontainers thay vì localhost mặc định.
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // --- PostgreSQL ---
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // --- Redis ---
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> ""); // redis:7-alpine không có password

        // --- RabbitMQ ---
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }
}

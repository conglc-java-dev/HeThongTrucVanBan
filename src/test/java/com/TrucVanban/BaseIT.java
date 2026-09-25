package com.TrucVanban;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ResourceReaper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

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
 *
 * <p><b>Colima support:</b> Tự động phát hiện Colima Docker socket và cấu hình
 * Testcontainers. Hoạt động trong cả IDE lẫn CLI mà không cần env vars.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
public abstract class BaseIT {

    // =====================================================================
    // Colima / Docker socket auto-detection — PHẢI chạy đầu tiên
    // trước khi bất kỳ Testcontainers container nào được khởi tạo.
    // =====================================================================
    static {
        configureDockerForColima();
    }

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

    /**
     * Tự động phát hiện Colima Docker socket và cấu hình Testcontainers.
     *
     * <p>Ưu tiên theo thứ tự:
     * <ol>
     *   <li>DOCKER_HOST env var đã được set → dùng luôn (không override)</li>
     *   <li>Colima socket tồn tại → set docker.host + disable Ryuk</li>
     *   <li>Không tìm thấy gì → dùng mặc định Testcontainers (Docker Desktop)</li>
     * </ol>
     *
     * <p><b>Tại sao dùng System.setProperty thay vì env var?</b>
     * Testcontainers đọc {@code docker.host} từ System properties trước env vars.
     * IDE không kế thừa env vars từ terminal nên System.setProperty là cách duy nhất
     * hoạt động trong cả 2 môi trường.
     */
    private static void configureDockerForColima() {
        String userHome = System.getProperty("user.home");
        File colimaSocket = new File(userHome + "/.colima/default/docker.sock");
        String envDockerHost = System.getenv("DOCKER_HOST");

        // 1. Docker host: Nếu DOCKER_HOST chưa set, trỏ vào Colima socket nếu tồn tại
        if ((envDockerHost == null || envDockerHost.isBlank()) && colimaSocket.exists()) {
            System.setProperty("docker.host", "unix://" + colimaSocket.getAbsolutePath());
        }

        // 2. Disable Ryuk nếu chạy với Colima
        boolean isColima = (envDockerHost != null && envDockerHost.contains("colima")) || colimaSocket.exists();
        if (isColima) {
            System.setProperty("testcontainers.ryuk.disabled", "true");
            disableRyuk();
        }
    }

    /**
     * Disable Ryuk container reaper khi chạy trên Colima.
     *
     * <p><b>Vấn đề:</b> Trên macOS với Colima, Docker socket nằm trong VM. Ryuk container
     * cố mount socket của host vào bên trong container dẫn đến lỗi {@code ContainerLaunchException}.
     *
     * <p><b>Giải pháp:</b> Testcontainers phiên bản hiện tại chỉ kiểm tra biến môi trường
     * {@code System.getenv("TESTCONTAINERS_RYUK_DISABLED")} khi gọi {@link ResourceReaper#instance()},
     * không đọc từ System property. Khi chạy test từ IntelliJ IDEA (GUI), các biến môi trường
     * từ shell profile (.zshrc) không được nạp.
     *
     * <p>Bằng cách gán trước singleton {@code ResourceReaper.instance} là instance của class gốc
     * {@link ResourceReaper} (thay vì {@code RyukResourceReaper}) thông qua reflection, Ryuk container
     * sẽ không bao giờ được khởi động, trong khi các container (Postgres, Redis, RabbitMQ) vẫn được
     * dọn dẹp sạch sẽ khi JVM kết thúc thông qua JVM shutdown hook của Testcontainers.
     */
    private static void disableRyuk() {
        try {
            Field instanceField = ResourceReaper.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            if (instanceField.get(null) == null) {
                Constructor<ResourceReaper> constructor = ResourceReaper.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                ResourceReaper plainReaper = constructor.newInstance();
                instanceField.set(null, plainReaper);
            }
        } catch (Exception e) {
            System.err.println("[BaseIT] Không thể khởi tạo ResourceReaper qua reflection: " + e.getMessage());
        }
    }
}

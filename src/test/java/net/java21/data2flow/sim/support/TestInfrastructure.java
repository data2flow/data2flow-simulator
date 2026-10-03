package net.java21.data2flow.sim.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

/**
 * 통합 시험 인프라(SIM test-plan "컨테이너"): Testcontainers PostgreSQL 18 + RabbitMQ 3.13(stream 플러그인). JVM에 하나씩만 띄워
 * 모든 IT가 함께 쓴다. 실제 s3·s4 인프라와 공용 브로커에는 붙지 않는다.
 */
public final class TestInfrastructure {

    public static final int STREAM_PORT = 5552;

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine").withDatabaseName("data2flow");
    @SuppressWarnings("resource")
    public static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withExposedPorts(STREAM_PORT, 5672, 15672)
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stream]."), "/etc/rabbitmq/enabled_plugins")
            // 스트림 클라이언트가 브로커가 알려 주는 주소 대신 매핑된 포트로 붙도록 advertised 값을 고정하지 않는다(주소 해석기로 처리)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private TestInfrastructure() {
    }

    public static String rabbitHost() {
        return RABBIT.getHost();
    }

    public static int amqpPort() {
        return RABBIT.getMappedPort(5672);
    }

    public static int streamPort() {
        return RABBIT.getMappedPort(STREAM_PORT);
    }
}
